/*
 * Copyright (C) 2009-2012 Johannes Rudolph and Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rosrabota.webrunner

import java.lang.{Runtime => JRuntime}

import sbt.{Logger, Process, ProjectRef}

/**
 * A token which we put into the SBT state to hold the Process of an application running in the background.
 */
case class AppProcess(projectRef: ProjectRef, log: Logger, fileWatcherThread: FileWatcherThread, onExit: (Int) => Any)(process: Process) {
  if (!fileWatcherThread.isAlive) fileWatcherThread.start()

  val shutdownHook = createShutdownHook("... killing ...")

  def createShutdownHook(msg: => String) =
    new Thread(new Runnable {
      def run() {
        if (isRunning) {
          log.info(msg)
          killProcess()
        }
      }
    })

  @volatile var finishState: Option[Int] = None

  val watchThread = {
    val thread = new Thread(new Runnable {
      def run() {
        val code = process.exitValue()
        finishState = Some(code)
        log.info("... finished with exit code " + code)
        fileWatcherThread.markStop()
        unregisterShutdownHook()
        Actions.unregisterAppProcess(projectRef)
        onExit(code)
      }
    })
    thread.start()
    thread
  }

  def projectName: String = projectRef.project

  def isCompiling: Boolean = fileWatcherThread.isCompiling
  def isCompileError: Boolean = fileWatcherThread.isCompileError

  def state: String =
    if (isCompiling) "compiling"
    else if (isCompileError) "compile-error"
    else if (isRunning) "running"
    else "stopped"

  registerShutdownHook()

  def stop() {
    fileWatcherThread.markStop()
    unregisterShutdownHook()
    killProcess()
    val code = process.exitValue()
    onExit(code)
  }

  def killProcess(): Unit = {
    process.destroy()
  }

  def registerShutdownHook() {
    JRuntime.getRuntime.addShutdownHook(shutdownHook)
  }

  def unregisterShutdownHook() {
    JRuntime.getRuntime.removeShutdownHook(shutdownHook)
  }

  def isRunning: Boolean =
    finishState.isEmpty
}