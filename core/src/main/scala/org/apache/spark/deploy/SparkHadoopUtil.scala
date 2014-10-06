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

package org.apache.spark.deploy

import java.lang.reflect.Field
import java.security.PrivilegedExceptionAction
import java.util.concurrent.ConcurrentHashMap

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.security.Credentials
import org.apache.hadoop.security.UserGroupInformation

import org.apache.spark.{Logging, SparkContext, SparkConf, SparkException}
import org.apache.spark.annotation.DeveloperApi

import scala.collection.JavaConversions._

/**
 * :: DeveloperApi ::
 * Contains util methods to interact with Hadoop from Spark.
 */
@DeveloperApi
class SparkHadoopUtil extends Logging {
  val conf: Configuration = newConfiguration(new SparkConf())
  UserGroupInformation.setConfiguration(conf)

  /**
   * Runs the given function with a Hadoop UserGroupInformation as a thread local variable
   * (distributed to child threads), used for authenticating HDFS and YARN calls.
   *
   * IMPORTANT NOTE: If this function is going to be called repeated in the same process
   * you need to look https://issues.apache.org/jira/browse/HDFS-3545 and possibly
   * do a FileSystem.closeAllForUGI in order to avoid leaking Filesystems
   */
  def runAsSparkUser(func: () => Unit) {
    val user = Option(System.getenv("SPARK_USER")).getOrElse(SparkContext.SPARK_UNKNOWN_USER)
    if (user != SparkContext.SPARK_UNKNOWN_USER) {
      logDebug("running as user: " + user)
      val ugi = UserGroupInformation.createRemoteUser(user)
      transferCredentials(UserGroupInformation.getCurrentUser(), ugi)
      ugi.doAs(new PrivilegedExceptionAction[Unit] {
        def run: Unit = func()
      })
    } else {
      logDebug("running as SPARK_UNKNOWN_USER")
      func()
    }
  }

  def transferCredentials(source: UserGroupInformation, dest: UserGroupInformation) {
    for (token <- source.getTokens()) {
      dest.addToken(token)
    }
  }

  @Deprecated
  def newConfiguration(): Configuration = newConfiguration(null)

  /**
   * Return an appropriate (subclass) of Configuration. Creating config can initializes some Hadoop
   * subsystems.
   */
  def newConfiguration(conf: SparkConf): Configuration = {
    val hadoopConf = new Configuration()

    // Note: this null check is around more than just access to the "conf" object to maintain
    // the behavior of the old implementation of this code, for backwards compatibility.
    if (conf != null) {
      // Explicitly check for S3 environment variables
      if (System.getenv("AWS_ACCESS_KEY_ID") != null &&
          System.getenv("AWS_SECRET_ACCESS_KEY") != null) {
        hadoopConf.set("fs.s3.awsAccessKeyId", System.getenv("AWS_ACCESS_KEY_ID"))
        hadoopConf.set("fs.s3n.awsAccessKeyId", System.getenv("AWS_ACCESS_KEY_ID"))
        hadoopConf.set("fs.s3.awsSecretAccessKey", System.getenv("AWS_SECRET_ACCESS_KEY"))
        hadoopConf.set("fs.s3n.awsSecretAccessKey", System.getenv("AWS_SECRET_ACCESS_KEY"))
      }
      // Copy any "spark.hadoop.foo=bar" system properties into conf as "foo=bar"
      conf.getAll.foreach { case (key, value) =>
        if (key.startsWith("spark.hadoop.")) {
          hadoopConf.set(key.substring("spark.hadoop.".length), value)
        }
      }
      val bufferSize = conf.get("spark.buffer.size", "65536")
      hadoopConf.set("io.file.buffer.size", bufferSize)
    }

    improveThreadSafety(hadoopConf)
  }

  /**
   * Wrap a Hadoop Configuration object to make it more threadsafe.  See SPARK-2546 for
   * an extensive discussion of how Configuration's lack of thread safety causes problems
   * and why we have to resort to reflection-based hacks to add thread safety.
   */
  private[spark] def improveThreadSafety(conf: Configuration): Configuration = {
    val confCopy = new Configuration(conf)

    def getDeclaredField(obj: Object, name: String): Field = {
      var cls: Class[_] = obj.getClass
      while (cls != null) {
        try {
          return cls.getDeclaredField(name)
        } catch {
          case e: NoSuchFieldException =>
            cls = cls.getSuperclass
        }
      }
      throw new NoSuchFieldException(s"Could not find field $name")
    }

    def wrapField[T](obj: Object, name: String)(wrapper: T => T) {
      try {
        val field = getDeclaredField(obj, name)
        val originalAccessibility = field.isAccessible
        try {
          field.setAccessible(true)
          val value: T = field.get(obj).asInstanceOf[T]
          if (value != null) {
            val updatedValue = wrapper(value)
            field.set(obj, updatedValue)
          }
        } finally {
          field.setAccessible(originalAccessibility)
        }
      } catch {
        case e: Exception =>
          logWarning(s"Exception when wrapping Configuration field $name for thread safety", e)
      }
    }

    wrapField[java.util.Map[String, Array[String]]](confCopy, "updatingResource") { existing =>
      new ConcurrentHashMap[String, Array[String]](existing)
    }

    confCopy
  }

  /**
   * Add any user credentials to the job conf which are necessary for running on a secure Hadoop
   * cluster.
   */
  def addCredentials(conf: JobConf) {}

  def isYarnMode(): Boolean = { false }

  def getCurrentUserCredentials(): Credentials = { null }

  def addCurrentUserCredentials(creds: Credentials) {}

  def addSecretKeyToUserCredentials(key: String, secret: String) {}

  def getSecretKeyFromUserCredentials(key: String): Array[Byte] = { null }

  def loginUserFromKeytab(principalName: String, keytabFilename: String) {
    UserGroupInformation.loginUserFromKeytab(principalName, keytabFilename)
  }

}

object SparkHadoopUtil {

  private val hadoop = {
    val yarnMode = java.lang.Boolean.valueOf(
        System.getProperty("SPARK_YARN_MODE", System.getenv("SPARK_YARN_MODE")))
    if (yarnMode) {
      try {
        Class.forName("org.apache.spark.deploy.yarn.YarnSparkHadoopUtil")
          .newInstance()
          .asInstanceOf[SparkHadoopUtil]
      } catch {
       case e: Exception => throw new SparkException("Unable to load YARN support", e)
      }
    } else {
      new SparkHadoopUtil
    }
  }

  def get: SparkHadoopUtil = {
    hadoop
  }
}
