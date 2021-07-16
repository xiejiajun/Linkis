/*
 * Copyright 2019 WeBank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
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

package com.webank.wedatasphere.linkis.resourcemanager.service

import com.webank.wedatasphere.linkis.common.ServiceInstance
import com.webank.wedatasphere.linkis.common.utils.Logging
import com.webank.wedatasphere.linkis.manager.common.entity.resource._
import com.webank.wedatasphere.linkis.resourcemanager.domain.RMLabelContainer
import com.webank.wedatasphere.linkis.resourcemanager.exception.RMWarnException
import com.webank.wedatasphere.linkis.resourcemanager.utils.RMUtils.aggregateResource
import com.webank.wedatasphere.linkis.resourcemanager.utils.{AlertUtils, RMConfiguration, UserConfiguration}

abstract class RequestResourceService(labelResourceService: LabelResourceService) extends Logging{

  val resourceType: ResourceType = ResourceType.Default

  def canRequest(labelContainer: RMLabelContainer, resource: NodeResource): Boolean = {
    var labelResource = labelResourceService.getLabelResource(labelContainer.getCurrentLabel)
    // for configuration resource
    if(labelContainer.getCurrentLabel.equals(labelContainer.getCombinedUserCreatorEngineTypeLabel)){
      if(labelResource == null) {
        labelResource = new CommonNodeResource
        labelResource.setResourceType(resource.getResourceType)
        labelResource.setUsedResource(Resource.initResource(resource.getResourceType))
        labelResource.setLockedResource(Resource.initResource(resource.getResourceType))
      }
      val configuredResource = UserConfiguration.getUserConfiguredResource(resource.getResourceType, labelContainer.getUserCreatorLabel, labelContainer.getEngineTypeLabel)
      info(s"Get configured resource ${configuredResource} for [${labelContainer.getUserCreatorLabel}] and [${labelContainer.getEngineTypeLabel}]")
      labelResource.setMaxResource(configuredResource)
      labelResource.setMinResource(Resource.initResource(labelResource.getResourceType))
      labelResource.setLeftResource(labelResource.getMaxResource - labelResource.getUsedResource - labelResource.getLockedResource)
    }
    info(s"Label [${labelContainer.getCurrentLabel}] has resource + [${labelResource }]")
    if(labelResource != null){
      val labelAvailableResource = labelResource.getLeftResource - labelResource.getMinResource
      if(labelAvailableResource < resource.getMinResource){
        info(s"Failed check: ${labelContainer.getUserCreatorLabel.getUser} want to use label [${labelContainer.getCurrentLabel}] resource[${resource.getMinResource}] > label available resource[${labelAvailableResource}]")
        // TODO sendAlert(moduleInstance, user, creator, requestResource, moduleAvailableResource.resource, moduleLeftResource)
        val notEnoughMessage = generateNotEnoughMessage(aggregateResource(labelResource.getUsedResource, labelResource.getLockedResource), labelAvailableResource)
        throw new RMWarnException(notEnoughMessage._1, notEnoughMessage._2)
      }
      info(s"Passed check: ${labelContainer.getUserCreatorLabel.getUser} want to use label [${labelContainer.getCurrentLabel}] resource[${resource.getMinResource}] <= label available resource[${labelAvailableResource}]")
    }
    warn(s"No resource available found for label ${labelContainer.getCurrentLabel}")
    return true
  }

  def sendAlert(moduleInstance: ServiceInstance, user: String, creator:String,  requestResource: Resource, availableResource: Resource, moduleLeftResource: Resource) = {
    if(RMConfiguration.ALERT_ENABLED.getValue){
      info("start sending alert")
      val title = s"user ${user} failed to request resource on EM(${moduleInstance.getApplicationName},${moduleInstance.getInstance})"
      val queueContact = requestResource match {
        case d: DriverAndYarnResource => AlertUtils.getContactByQueue(d.yarnResource.queueName)
        case y: YarnResource => AlertUtils.getContactByQueue(y.queueName)
        case _ => RMConfiguration.ALERT_DEFAULT_CONTACT.getValue
      }
      val detail =
        s"请联系用户[${user}]或相关人员[${queueContact}]\n" +
          s"user request resource: ${requestResource}\n " +
          s"user available resource: ${availableResource}\n " +
          s"EM left resource: ${moduleLeftResource}\n "
      AlertUtils.sendAlertAsync(title, detail);
      info("finished sending alert")
    }
  }

  def generateNotEnoughMessage(requestResource: Resource, availableResource: Resource) : (Int, String) = {
    requestResource match {
      case m: MemoryResource =>
        (11011, s"The remote server is out of memory resources(远程服务器内存资源不足。)")
      case c: CPUResource =>
        (11012, s"Insufficient CPU resources on remote server (远程服务器CPU资源不足。)")
      case i: InstanceResource =>
        (11013, s"The remote server is out of resources (远程服务器资源不足。)")
      case l: LoadResource =>
        val loadAvailable = availableResource.asInstanceOf[LoadResource]
        if(l.cores > loadAvailable.cores){
          (11012, s"Insufficient CPU resources on remote server (远程服务器CPU资源不足。)")
        } else {
          (11011, s"The remote server is out of memory resources(远程服务器内存资源不足。)")
        }
      case li: LoadInstanceResource =>
        val loadInstanceAvailable = availableResource.asInstanceOf[LoadInstanceResource]
        if(li.cores > loadInstanceAvailable.cores){
          (11012, s"Insufficient CPU resources on remote server (远程服务器CPU资源不足。)")
        } else if (li.memory > loadInstanceAvailable.memory) {
          (11011, s"The remote server is out of memory resources(远程服务器内存资源不足。)")
        } else {
          (11013, s"The remote server is out of resources (远程服务器资源不足。)")
        }
      case yarn: YarnResource =>
        val yarnAvailable = availableResource.asInstanceOf[YarnResource]
        if(yarn.queueCores > yarnAvailable.queueCores){
          (11014, s"The queue CPU resources are insufficient, it is recommended to reduce the number of executors(队列CPU资源不足，建议调小执行器个数。)")
        } else if (yarn.queueMemory > yarnAvailable.queueMemory){
          (11015, s"Insufficient queue memory resources, it is recommended to reduce the actuator memory (队列内存资源不足，建议调小执行器内存。)")
        } else {
          (11016, s"Number of queue instances exceeds limit (队列实例数超过限制。)")
        }
      case dy: DriverAndYarnResource =>
        val dyAvailable = availableResource.asInstanceOf[DriverAndYarnResource]
        if(dy.loadInstanceResource.memory > dyAvailable.loadInstanceResource.memory ||
          dy.loadInstanceResource.cores > dyAvailable.loadInstanceResource.cores ||
          dy.loadInstanceResource.instances > dyAvailable.loadInstanceResource.instances){
          val detail = generateNotEnoughMessage(dy.loadInstanceResource, dyAvailable.loadInstanceResource)
          (detail._1, s"Request a server resource, ${detail._2} 请求服务器资源时，${detail._2}")
        } else {
          val detail = generateNotEnoughMessage(dy.yarnResource, dyAvailable.yarnResource)
          (detail._1, s"Request a server resource, ${detail._2} 请求队列资源时，${detail._2}")
        }
      case s: SpecialResource => throw new RMWarnException(11003,"not supported resource type " + s.getClass)
      case r: Resource => throw new RMWarnException(11003,"not supported resource type " + r.getClass)
    }
  }
}

