/*
 * Copyright 2019 WeBank
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

package com.webank.wedatasphere.linkis.orchestrator.ecm.service

import com.webank.wedatasphere.linkis.governance.common.protocol.task._
import com.webank.wedatasphere.linkis.message.builder.ServiceMethodContext



trait TaskExecutionReceiver {


  def taskLogReceiver(taskLog: ResponseTaskLog, smc: ServiceMethodContext): Unit

  def taskProgressReceiver(taskProgress: ResponseTaskProgress, smc: ServiceMethodContext): Unit

  def taskStatusReceiver(taskStatus: ResponseTaskStatus, smc: ServiceMethodContext): Unit

  def taskResultSizeReceiver(taskResultSize: ResponseTaskResultSize, smc: ServiceMethodContext): Unit

  def taskResultSetReceiver(taskResultSet: ResponseTaskResultSet, smc: ServiceMethodContext): Unit

  def taskErrorReceiver(responseTaskError: ResponseTaskError, smc: ServiceMethodContext): Unit
}
