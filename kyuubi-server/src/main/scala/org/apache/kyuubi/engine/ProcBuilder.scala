/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.engine

import java.io.{File, FilenameFilter, IOException}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

import com.google.common.collect.EvictingQueue
import org.apache.commons.lang3.StringUtils.containsIgnoreCase

import org.apache.kyuubi._
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.KYUUBI_HOME
import org.apache.kyuubi.operation.log.OperationLog
import org.apache.kyuubi.util.NamedThreadFactory

trait ProcBuilder {

  import ProcBuilder._

  /**
   * The short name of the engine process builder, we use this for form the engine jar paths now
   * see `mainResource`
   */
  protected def shortName: String

  /**
   * executable, it is `JAVA_HOME/bin/java` by default
   */
  protected def executable: String = {
    val javaHome = env.get("JAVA_HOME")
    if (javaHome.isEmpty) {
      throw validateEnv("JAVA_HOME")
    } else {
      Paths.get(javaHome.get, "bin", "java").toString
    }
  }

  /**
   * The engine jar or other runnable jar containing the main method
   */
  def mainResource: Option[String] = {
    // 1. get the main resource jar for user specified config first
    // TODO use SPARK_SCALA_VERSION instead of SCALA_COMPILE_VERSION
    val jarName = s"${module}_$SCALA_COMPILE_VERSION-$KYUUBI_VERSION.jar"
    conf.getOption(s"kyuubi.session.engine.$shortName.main.resource").filter { userSpecified =>
      // skip check exist if not local file.
      val uri = new URI(userSpecified)
      val schema = if (uri.getScheme != null) uri.getScheme else "file"
      schema match {
        case "file" => Files.exists(Paths.get(userSpecified))
        case _ => true
      }
    }.orElse {
      // 2. get the main resource jar from system build default
      env.get(KYUUBI_HOME).toSeq
        .flatMap { p =>
          Seq(
            Paths.get(p, "externals", "engines", shortName, jarName),
            Paths.get(p, "externals", module, "target", jarName))
        }
        .find(Files.exists(_)).map(_.toAbsolutePath.toFile.getCanonicalPath)
    }.orElse {
      // 3. get the main resource from dev environment
      val cwd = Utils.getCodeSourceLocation(getClass).split("kyuubi-server")
      assert(cwd.length > 1)
      Option(Paths.get(cwd.head, "externals", module, "target", jarName))
        .map(_.toAbsolutePath.toFile.getCanonicalPath)
    }
  }

  protected def module: String

  /**
   * The class containing the main method
   */
  protected def mainClass: String

  protected def proxyUser: String

  protected def commands: Array[String]

  protected def conf: KyuubiConf

  protected def env: Map[String, String] = conf.getEnvs

  protected def childProcEnv: Map[String, String] = env

  protected val extraEngineLog: Option[OperationLog]

  protected val workingDir: Path = {
    env.get("KYUUBI_WORK_DIR_ROOT").map { root =>
      val workingRoot = Paths.get(root).toAbsolutePath
      if (!Files.exists(workingRoot)) {
        debug(s"Creating KYUUBI_WORK_DIR_ROOT at $workingRoot")
        Files.createDirectories(workingRoot)
      }
      if (Files.isDirectory(workingRoot)) {
        workingRoot.toString
      } else null
    }.map { rootAbs =>
      val working = Paths.get(rootAbs, proxyUser)
      if (!Files.exists(working)) {
        debug(s"Creating $proxyUser's working directory at $working")
        Files.createDirectories(working)
      }
      if (Files.isDirectory(working)) {
        working
      } else {
        Utils.createTempDir(rootAbs, proxyUser)
      }
    }.getOrElse {
      Utils.createTempDir(namePrefix = proxyUser)
    }
  }

  final lazy val processBuilder: ProcessBuilder = {
    val pb = new ProcessBuilder(commands: _*)

    val envs = pb.environment()
    envs.putAll(childProcEnv.asJava)
    pb.directory(workingDir.toFile)
    pb.redirectError(engineLog)
    pb.redirectOutput(engineLog)
    extraEngineLog.foreach(_.addExtraLog(engineLog.toPath))
    pb
  }

  @volatile private var error: Throwable = UNCAUGHT_ERROR

  private val engineLogMaxLines = conf.get(KyuubiConf.SESSION_ENGINE_STARTUP_MAX_LOG_LINES)
  private val waitCompletion = conf.get(KyuubiConf.SESSION_ENGINE_STARTUP_WAIT_COMPLETION)
  protected val lastRowsOfLog: EvictingQueue[String] = EvictingQueue.create(engineLogMaxLines)
  // Visible for test
  @volatile private[kyuubi] var logCaptureThreadReleased: Boolean = true
  private var logCaptureThread: Thread = _
  private var process: Process = _

  private[kyuubi] lazy val engineLog: File = ProcBuilder.synchronized {
    val engineLogTimeout = conf.get(KyuubiConf.ENGINE_LOG_TIMEOUT)
    val currentTime = System.currentTimeMillis()
    val processLogPath = workingDir
    val totalExistsFile = processLogPath.toFile.listFiles { (_, name) => name.startsWith(module) }
    val sorted = totalExistsFile.sortBy(_.getName.split("\\.").last.toInt)
    val nextIndex =
      if (sorted.isEmpty) {
        0
      } else {
        sorted.last.getName.split("\\.").last.toInt + 1
      }
    val file = sorted.find(_.lastModified() < currentTime - engineLogTimeout)
      .map { existsFile =>
        try {
          // Here we want to overwrite the exists log file
          existsFile.delete()
          existsFile.createNewFile()
          existsFile
        } catch {
          case e: Exception =>
            warn(s"failed to delete engine log file: ${existsFile.getAbsolutePath}", e)
            null
        }
      }
      .getOrElse {
        Files.createDirectories(processLogPath)
        val newLogFile = new File(processLogPath.toFile, s"$module.log.$nextIndex")
        newLogFile.createNewFile()
        newLogFile
      }
    file.setLastModified(currentTime)
    info(s"Logging to $file")
    file
  }

  final def start: Process = synchronized {
    process = processBuilder.start()
    val reader = Files.newBufferedReader(engineLog.toPath, StandardCharsets.UTF_8)

    val redirect: Runnable = { () =>
      try {
        val maxErrorSize = conf.get(KyuubiConf.ENGINE_ERROR_MAX_SIZE)
        while (true) {
          if (reader.ready()) {
            var line: String = reader.readLine()
            if (containsException(line) &&
              !line.contains("at ") && !line.startsWith("Caused by:")) {
              val sb = new StringBuilder(line)
              error = KyuubiSQLException(sb.toString() + s"\n See more: $engineLog")
              line = reader.readLine()
              while (sb.length < maxErrorSize && line != null &&
                (containsException(line) ||
                  line.startsWith("\tat ") ||
                  line.startsWith("Caused by: "))) {
                sb.append("\n" + line)
                line = reader.readLine()
              }

              error = KyuubiSQLException(sb.toString() + s"\n See more: $engineLog")
            } else if (line != null) {
              lastRowsOfLog.add(line)
            }
          } else {
            Thread.sleep(300)
          }
        }
      } catch {
        case _: IOException =>
        case _: InterruptedException =>
      } finally {
        logCaptureThreadReleased = true
        reader.close()
      }
    }

    logCaptureThreadReleased = false
    logCaptureThread = PROC_BUILD_LOGGER.newThread(redirect)
    logCaptureThread.start()
    process
  }

  /**
   * Use Left to represent engineRefId and Right to represent line.
   */
  def killApplication(clue: Either[String, String] = Right(lastRowsOfLog.toArray.mkString("\n")))
      : String = ""

  def close(): Unit = synchronized {
    if (logCaptureThread != null) {
      logCaptureThread.interrupt()
      logCaptureThread = null
    }
    if (!waitCompletion && process != null) {
      info("Destroy the process, since waitCompletion is false.")
      process.destroyForcibly()
      process = null
    }
  }

  def getError: Throwable = synchronized {
    if (error == UNCAUGHT_ERROR) {
      Thread.sleep(1000)
    }
    val lastLogRows = lastRowsOfLog.toArray.mkString("\n")
    error match {
      case UNCAUGHT_ERROR =>
        KyuubiSQLException(s"Failed to detect the root cause, please check $engineLog at server " +
          s"side if necessary. The last $engineLogMaxLines line(s) of log are:\n" +
          s"${lastRowsOfLog.toArray.mkString("\n")}")
      case other =>
        KyuubiSQLException(s"${Utils.stringifyException(other)}.\n" +
          s"FYI: The last $engineLogMaxLines line(s) of log are:\n$lastLogRows")
    }
  }

  private def containsException(log: String): Boolean =
    containsIgnoreCase(log, "Exception:") || containsIgnoreCase(log, "Exception in thread")

  override def toString: String = {
    if (commands == null) {
      super.toString()
    } else {
      commands.map {
        case arg if arg.startsWith("--") => s"\\\n\t$arg"
        case arg => arg
      }.mkString(" ")
    }
  }

  /**
   * Get the home directly that contains binary distributions of engines.
   *
   * Take Spark as an example, we first lookup the SPARK_HOME from user specified environments.
   * If not found, we assume that it is a dev environment and lookup the kyuubi-download's output
   * directly. If not found again, a `KyuubiSQLException` will be raised.
   * In summarize, we walk through
   *   `kyuubi.engineEnv.SPARK_HOME` ->
   *   System.env("SPARK_HOME") ->
   *   kyuubi-download/target/spark-* ->
   *   error.
   *
   * @param shortName the short name of engine, e.g. spark
   * @return SPARK_HOME, HIVE_HOME, etc.
   */
  protected def getEngineHome(shortName: String): String = {
    val homeKey = s"${shortName.toUpperCase}_HOME"
    val homeVal = env.get(homeKey).orElse {
      val cwd = Utils.getCodeSourceLocation(getClass).split("kyuubi-server")
      assert(cwd.length > 1)
      Option(
        Paths.get(cwd.head)
          .resolve("externals")
          .resolve("kyuubi-download")
          .resolve("target")
          .toFile
          .listFiles(new FilenameFilter {
            override def accept(dir: File, name: String): Boolean = {
              dir.isDirectory && name.contains(s"$shortName-")
            }
          }))
        .flatMap(_.headOption)
        .map(_.getAbsolutePath)
    }
    if (homeVal.isEmpty) {
      throw validateEnv(homeKey)
    } else {
      homeVal.get
    }
  }

  protected def validateEnv(requiredEnv: String): Throwable = {
    KyuubiSQLException(s"$requiredEnv is not set! For more information on installing and " +
      s"configuring $requiredEnv, please visit https://kyuubi.apache.org/docs/latest/" +
      s"deployment/settings.html#environments")
  }

}

object ProcBuilder extends Logging {
  private val PROC_BUILD_LOGGER = new NamedThreadFactory("process-logger-capture", daemon = true)

  private val UNCAUGHT_ERROR = new RuntimeException("Uncaught error")
}
