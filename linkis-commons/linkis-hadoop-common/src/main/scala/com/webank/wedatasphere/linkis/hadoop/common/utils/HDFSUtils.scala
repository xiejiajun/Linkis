/*
 * Copyright 2019 WeBank
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.wedatasphere.linkis.hadoop.common.utils

import java.io.File
import java.nio.file.Paths
import java.security.PrivilegedExceptionAction

import com.webank.wedatasphere.linkis.hadoop.common.conf.HadoopConf
import com.webank.wedatasphere.linkis.hadoop.common.conf.HadoopConf.{hadoopConfDir, _}
import org.apache.commons.lang.StringUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.security.UserGroupInformation


object HDFSUtils {


  def getConfiguration(user: String): Configuration = getConfiguration(user, hadoopConfDir)

  def getConfigurationByLabel(user: String, label: String): Configuration = {
    getConfiguration(user, getHadoopConDirByLabel(label))
  }

  private def getHadoopConDirByLabel(label: String): String = {
    if (StringUtils.isBlank(label)) {
      hadoopConfDir
    } else {
      val prefix = if (HadoopConf.HADOOP_EXTERNAL_CONF_DIR_PREFIX.getValue.endsWith("/")) {
        HadoopConf.HADOOP_EXTERNAL_CONF_DIR_PREFIX.getValue
      } else {
        HadoopConf.HADOOP_EXTERNAL_CONF_DIR_PREFIX.getValue + "/"
      }
      prefix + label
    }
  }

  def getConfiguration(user: String, hadoopConfDir: String): Configuration = {
    val confPath = new File(hadoopConfDir)
    if (!confPath.exists() || confPath.isFile) {
      throw new RuntimeException(s"Create hadoop configuration failed, path $hadoopConfDir not exists.")
    }
    val conf = new Configuration()
    conf.addResource(new Path(Paths.get(hadoopConfDir, "core-site.xml").toAbsolutePath.toFile.getAbsolutePath))
    conf.addResource(new Path(Paths.get(hadoopConfDir, "hdfs-site.xml").toAbsolutePath.toFile.getAbsolutePath))
    conf
  }

  def getHDFSRootUserFileSystem: FileSystem = getHDFSRootUserFileSystem(getConfiguration(HADOOP_ROOT_USER.getValue))

  def getHDFSRootUserFileSystem(conf: org.apache.hadoop.conf.Configuration): FileSystem =
    getHDFSUserFileSystem(HADOOP_ROOT_USER.getValue, conf)

  def getHDFSUserFileSystem(userName: String): FileSystem = getHDFSUserFileSystem(userName, getConfiguration(userName))

  def getHDFSUserFileSystem(userName: String, conf: org.apache.hadoop.conf.Configuration): FileSystem =
    getUserGroupInformation(userName)
      .doAs(new PrivilegedExceptionAction[FileSystem]{
        def run = FileSystem.get(conf)
      })
  def getUserGroupInformation(userName: String): UserGroupInformation ={
    if(KERBEROS_ENABLE.getValue) {
      val path = new File(KEYTAB_FILE.getValue , userName + ".keytab").getPath
      val user = getKerberosUser(userName)
      UserGroupInformation.setConfiguration(getConfiguration(userName))
      UserGroupInformation.loginUserFromKeytabAndReturnUGI(user, path)
    } else {
      UserGroupInformation.createRemoteUser(userName)
    }
  }

  def getKerberosUser(userName: String): String = {
    var user = userName
    if(KEYTAB_HOST_ENABLED.getValue){
      user = user + "/" + KEYTAB_HOST.getValue
    }
    user
  }

}
