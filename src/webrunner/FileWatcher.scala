package webrunner

import java.io.File
import java.nio.file.StandardWatchEventKinds._
import java.nio.file.{FileSystems, WatchEvent, WatchKey, WatchService, Path => JPath}
import java.util.concurrent.TimeUnit

import sbt.FileFilter

import scala.annotation.tailrec
import scala.collection.mutable

class FileWatcher {
  private val watcher: WatchService = FileSystems.getDefault.newWatchService()

  def addDir(dirFile: File): Unit = {
    dirFile.toPath.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
  }

  def addDirRecursively(dirFile: File): Unit = {
    if (dirFile.exists() && dirFile.isDirectory) {
      addDir(dirFile)
      for (subFile <- dirFile.listFiles() if subFile.isDirectory) {
        addDirRecursively(subFile)
      }
    }
  }

  def poll(filter: FileFilter): Set[File] = check(watcher.poll(), filter)
  def poll(filter: FileFilter, timeout: Long, unit: TimeUnit): Set[File] = check(watcher.poll(timeout, unit), filter)
  def take(filter: FileFilter): Set[File] = {
    while (true) {
      val files = check(watcher.take(), filter)
      if (files.nonEmpty) return files
    }
    sys.error("")
  }

  def close(): Unit = {
    watcher.close()
  }

  @tailrec private def check(key: WatchKey, filter: FileFilter, lastResult: Set[File] = Set.empty): Set[File] = {
    if (key == null) lastResult
    else {
      val changedFiles: mutable.Set[File] = mutable.Set.empty
      key.pollEvents().forEach {event =>
        event.kind() match {
          case OVERFLOW => changedFiles ++= lastResult
          case _ =>
            val localPath: JPath = event.asInstanceOf[WatchEvent[JPath]].context()
            val file: File = key.watchable().asInstanceOf[JPath].resolve(localPath).toFile
            if (filter.accept(file)) changedFiles += file

            if (file.isDirectory && (event.kind() == ENTRY_CREATE || event.kind() == ENTRY_MODIFY)) {
              // New directory added, or renamed. We must include it in the watch list.
              addDirRecursively(file)
            }
        }
      }
      // idea сохраняет файлы через переименование, поэтому следует немного подождать, пока процесс завершится
      // это позволяет избежать короткой перекомпиляции сразу после компиляции
      key.reset()
      Thread.sleep(20)
      check(watcher.poll(), filter, lastResult ++ changedFiles)
    }
  }
}

object FileWatcher {
  def wrap[T](body: FileWatcher => T): T = {
    val watcher: FileWatcher = new FileWatcher
    try body(watcher)
    finally watcher.close()
  }
}