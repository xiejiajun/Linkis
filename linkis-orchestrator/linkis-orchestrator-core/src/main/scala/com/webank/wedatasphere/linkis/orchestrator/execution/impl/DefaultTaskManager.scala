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

package com.webank.wedatasphere.linkis.orchestrator.execution.impl

import com.webank.wedatasphere.linkis.common.listener.Event
import com.webank.wedatasphere.linkis.common.utils.Logging
import com.webank.wedatasphere.linkis.governance.common.entity.ExecutionNodeStatus
import com.webank.wedatasphere.linkis.orchestrator.conf.OrchestratorConfiguration
import com.webank.wedatasphere.linkis.orchestrator.execution._
import com.webank.wedatasphere.linkis.orchestrator.listener.execution.ExecutionTaskCompletedEvent
import com.webank.wedatasphere.linkis.orchestrator.listener.task._
import com.webank.wedatasphere.linkis.orchestrator.listener.{OrchestratorListenerBusContext, OrchestratorSyncEvent, OrchestratorSyncListenerBus}
import com.webank.wedatasphere.linkis.orchestrator.plans.physical.ExecTask

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import java.util
import java.util.concurrent
import java.util.concurrent.CopyOnWriteArrayList

/**
  *
  *
  */
class DefaultTaskManager extends AbstractTaskManager with Logging {


  /**
    * executionTasks
    */
  private val executionTasks: util.List[ExecutionTask] = new CopyOnWriteArrayList[ExecutionTask]()

  /**
    * key: execTaskID
    * value: ExecutionTask in running
    */
  private val execTaskToExecutionTask: mutable.Map[String, ExecutionTask] = new mutable.HashMap[String, ExecutionTask]()

  /**
    * key: ExecutionTaskID
    * value: Array ExecTaskRunner in running
    */
  private val executionTaskToRunningExecTask: mutable.Map[String, ArrayBuffer[ExecTaskRunner]] = new mutable.HashMap[String, ArrayBuffer[ExecTaskRunner]]()

  /**
    * key:  ExecutionTaskID
    * value: Array ExecTaskRunner in completed
    */
  private val executionTaskToCompletedExecTask: mutable.Map[String, ArrayBuffer[ExecTaskRunner]] = new mutable.HashMap[String, ArrayBuffer[ExecTaskRunner]]()

  private val MAX_RUNNER_TASK_SIZE = OrchestratorConfiguration.TASK_RUNNER_MAX_SIZE.getValue

  //private val syncListenerBus: OrchestratorSyncListenerBus = OrchestratorListenerBusContext.getListenerBusContext().getOrchestratorSyncListenerBus

  private val userRunningNumber: UserRunningNumber = new UserRunningNumber


  /**
    * create ExecutionTask
    *
    * @param task
    * @return
    */
  override def putExecTask(task: ExecTask): ExecutionTask = {
    if (null != task) {
      val executionTask = new BaseExecutionTask(OrchestratorConfiguration.EXECUTION_TASK_MAX_PARALLELISM.getValue, task)
      executionTasks.add(executionTask)
      execTaskToExecutionTask.put(task.getId, executionTask)
      info(s"submit execTask ${task.getIDInfo()} to taskManager get executionTask ${executionTask.getId}")
      task.getPhysicalContext.broadcastAsyncEvent(TaskConsumerEvent(task))
      return executionTask
    }
    null
  }

  def getRunningExecutionTasks: Array[String] = executionTaskToRunningExecTask.keysIterator.toArray

  override def getRunningTask(executionTaskId: String): Array[ExecTaskRunner] = {
    executionTaskToRunningExecTask.get(executionTaskId).map(_.toArray).getOrElse(Array.empty)
  }

  override def getRunningTask(task: ExecTask): Array[ExecTaskRunner] = {
    val executionTask = execTaskToExecutionTask.getOrElse(task.getId, null)
    if (null != executionTask) {
      executionTaskToRunningExecTask.get(executionTask.getId).map(_.toArray).getOrElse(Array.empty)
    } else {
      Array.empty
    }
  }

  override def getCompletedTasks(executionTaskId: String): Array[ExecTaskRunner] =
    executionTaskToCompletedExecTask.get(executionTaskId).map(_.toArray).getOrElse(Array.empty)

  override def getCompletedTasks(task: ExecTask): Array[ExecTaskRunner] = execTaskToExecutionTask.get(task.getId)
    .map(executionTask => executionTaskToCompletedExecTask.get(executionTask.getId).map(_.toArray).getOrElse(Array.empty)).getOrElse(Array.empty)

  def getRunnableExecutionTasks: Array[ExecutionTask] = getSuitableExecutionTasks.filter { executionTask =>
    val execTask = executionTask.getRootExecTask
    val subTasks = new java.util.HashSet[ExecTask]()
    getSubTasksRecursively(executionTask, execTask, subTasks)
    !subTasks.isEmpty
  }

  protected def getSuitableExecutionTasks: Array[ExecutionTask] = {
    executionTasks.asScala.filter(executionTask => executionTask.getRootExecTask.canExecute
      && !ExecutionNodeStatus.isCompleted(executionTask.getStatus)).toArray
  }

  /**
    * Get runnable TaskRunner
    * 1. Polling for all outstanding ExecutionTasks
    * 2. Polling for unfinished subtasks of ExecutionTask corresponding to ExecTask tree
    * 3. Get the subtask and determine whether it exceeds the maximum value of getRunnable. If it exceeds the maximum value, the maximum number of tasks will be returned
    *
    * @return
    */
  override def getRunnableTasks: Array[ExecTaskRunner] = {
    val startTime = System.currentTimeMillis()
    debug(s"Start to getRunnableTasks startTime: $startTime")
    val execTaskRunners = ArrayBuffer[ExecTaskRunner]()
    val runningExecutionTasks = getSuitableExecutionTasks
    //1. Get all runnable TaskRunner
    runningExecutionTasks.foreach { executionTask =>
      val execTask = executionTask.getRootExecTask
      val subTasks = new java.util.HashSet[ExecTask]()
      getSubTasksRecursively(executionTask, execTask, subTasks)
      val subExecTaskRunners = subTasks.asScala.map(execTaskToTaskRunner)
      execTaskRunners ++= subExecTaskRunners
    }

    //2. Take the current maximum number of runnables from the priority queue: Maximum limit-jobs that are already running
    val nowRunningNumber = executionTaskToRunningExecTask.values.map(_.length).sum
    val maxRunning = if (nowRunningNumber >= MAX_RUNNER_TASK_SIZE) 0 else MAX_RUNNER_TASK_SIZE - nowRunningNumber
    val runnableTasks = if (maxRunning == 0) {
      warn(s"The current running has exceeded the maximum, now: $nowRunningNumber ")
      Array.empty[ExecTaskRunner]
    } else if (execTaskRunners.isEmpty) {
      debug("There are no tasks to run now")
      Array.empty[ExecTaskRunner]
    } else {
      //3. create priorityQueue Scoring rules: End type tasks are 100 points, userMax-runningNumber (remaining ratio) is additional points
      val userTaskRunnerQueue = new UserTaskRunnerPriorityQueue
      userTaskRunnerQueue.addAll(execTaskRunners.toArray, userRunningNumber.copy())
      val userTaskRunners = userTaskRunnerQueue.takeTaskRunner(maxRunning)
      val runners = new ArrayBuffer[ExecTaskRunner]()
      userTaskRunners.foreach { userTaskRunner =>
        val execTask = userTaskRunner.taskRunner.task
        val executionTask = execTaskToExecutionTask.get(execTask.getPhysicalContext.getRootTask.getId)
        if (executionTask.isDefined) {
          val executionTaskId = executionTask.get.getId
          val runningExecTaskRunner = executionTaskToRunningExecTask.getOrElseUpdate(executionTaskId, new ArrayBuffer[ExecTaskRunner]())
          runningExecTaskRunner += userTaskRunner.taskRunner
          runners += userTaskRunner.taskRunner
        }
      }
      runners.toArray
    }
    val finishTime = System.currentTimeMillis()
    debug(s"Finished to getRunnableTasks finishTime: $finishTime, taken: ${finishTime - startTime}")
    runnableTasks
  }

  /**
    * Modified TaskRunner
    * The runningExecTaskMap needs to be removed and cleaned
    * Need to increase the difference of completedExecTaskMap
    *
    * @param task
    */
  override def addCompletedTask(task: ExecTaskRunner): Unit = {
    val rootTask = task.task.getPhysicalContext.getRootTask
    execTaskToExecutionTask.get(rootTask.getId).foreach { executionTask =>
      executionTaskToRunningExecTask synchronized {
        val oldRunningTasks = executionTaskToRunningExecTask.getOrElse(executionTask.getId, null)
        //from running ExecTasks to remove completed execTasks
        if (null != oldRunningTasks) {
          val runningRunners = oldRunningTasks.filterNot(runner => task.task.getId.equals(runner.task.getId))
          executionTaskToRunningExecTask.put(executionTask.getId, runningRunners)
        }
      }
      //put completed execTasks to completed collections
      executionTaskToCompletedExecTask synchronized {
        val completedRunners = executionTaskToCompletedExecTask.getOrElseUpdate(executionTask.getId, new ArrayBuffer[ExecTaskRunner]())
        if (!completedRunners.exists(_.task.getId.equals(task.task.getId))) {
          completedRunners += task
          userRunningNumber.minusNumber(task.task.getTaskDesc.getOrigin.getASTOrchestration.getASTContext.getExecuteUser)
        }
      }
    }
    rootTask.getPhysicalContext.broadcastAsyncEvent(TaskConsumerEvent(task.task))
  }


  /**
    * TODO executionTaskAndRootExecTask Will clean up, the data is not the most complete, you need to consider storing the removed to the persistence layer
    *
    * @return
    */
  override def pollCompletedExecutionTasks: Array[ExecutionTask] = {
    executionTasks.asScala.filter(executionTask => ExecutionNodeStatus.isCompleted(executionTask.getStatus)).toArray
  }


  /**
    * Recursively obtain tasks that can be run under ExecutionTask
    * 1. First judge whether the child node of the corresponding node is completed, and if the operation is completed, submit the node (recursive exit condition 1)
    * 2. If there are child nodes that are not completed, submit the child nodes recursively
    * 3. If the obtained task exceeds the maximum concurrent number of ExecutionTask, return directly (recursive exit condition 2)
    * TODO  Whether needs to do strict maximum task concurrency control, exit condition 2 also needs to consider the task currently running
    *
    * @param executionTask
    * @param execTask
    * @param subTasks
    */
  private def getSubTasksRecursively(executionTask: ExecutionTask, execTask: ExecTask, subTasks: java.util.Set[ExecTask]): Unit = {
    if (subTasks.size > executionTask.getMaxParallelism || isExecuted(executionTask, execTask)) return
    val tasks = findUnCompletedExecTasks(executionTask.getId, execTask.getChildren)
    if (null == tasks || tasks.isEmpty) {
      subTasks.add(execTask)
    } else {
      //递归子节点
      tasks.foreach(getSubTasksRecursively(executionTask, _, subTasks))
    }
  }

  private def isExecuted(executionTask: ExecutionTask, execTask: ExecTask): Boolean = {
    val runningDefined = executionTaskToRunningExecTask.get(executionTask.getId).exists(_.exists(_.task.getId.equals(execTask.getId)))
    val completedDefined = executionTaskToCompletedExecTask.get(executionTask.getId).exists(_.exists(_.task.getId.equals(execTask.getId)))
    runningDefined || completedDefined
  }

  /**
    * from tasks to find unCompleted ExecTasks
    *
    * @param executionTaskId
    * @param tasks
    * @return
    */
  private def findUnCompletedExecTasks(executionTaskId: String, tasks: Array[ExecTask]): Array[ExecTask] = {
    val maybeRunners = executionTaskToCompletedExecTask.get(executionTaskId)
    if (maybeRunners.isDefined) {
      val completedTask = maybeRunners.get
      tasks.filter(execTask => !completedTask.exists(_.task.getId.equals(execTask.getId)))
    } else {
      tasks
    }
  }


  protected def execTaskToTaskRunner(execTask: ExecTask): ExecTaskRunner = {
    val execTaskRunner = ExecTaskRunner.getExecTaskRunnerFactory.createExecTaskRunner(execTask)
    execTaskRunner
  }


  override def onSyncEvent(event: OrchestratorSyncEvent): Unit = event match {
    case rootTaskResponseEvent: RootTaskResponseEvent =>
      onRootTaskResponseEvent(rootTaskResponseEvent)
    case _ =>

  }


  private def clearExecutionTask(executionTask: ExecutionTask): Unit = {
    // from executionTask to remove executionTask
    executionTasks.remove(executionTask)
    // from execTaskToExecutionTask to remove root execTask
    execTaskToExecutionTask.remove(executionTask.getRootExecTask.getId)
    // from executionTaskToCompletedExecTask to remove executionTask
    executionTaskToCompletedExecTask.remove(executionTask.getId)
    executionTaskToRunningExecTask.remove(executionTask.getId).foreach(_.foreach(execTaskRunner => execTaskRunner.interrupt()))
  }

  override def onRootTaskResponseEvent(rootTaskResponseEvent: RootTaskResponseEvent): Unit = {
    info(s"received rootTaskResponseEvent ${rootTaskResponseEvent.execTask.getIDInfo()}")
    val rootTask = rootTaskResponseEvent.execTask
    val maybeTask = execTaskToExecutionTask.get(rootTask.getId)
    if (maybeTask.isDefined) {
      val executionTask = maybeTask.get
      rootTaskResponseEvent.taskResponse match {
        case failedTaskResponse: FailedTaskResponse =>
          markExecutionTaskCompleted(executionTask, failedTaskResponse)
        case succeedTaskResponse: SucceedTaskResponse =>
          markExecutionTaskCompleted(executionTask, succeedTaskResponse)
      }
    }

  }


  override protected def markExecutionTaskCompleted(executionTask: ExecutionTask, taskResponse: CompletedTaskResponse): Unit = {
    info(s"Start to mark executionTask(${executionTask.getId}) to  Completed.")
    clearExecutionTask(executionTask)
    executionTask.getRootExecTask.getPhysicalContext.broadcastSyncEvent(ExecutionTaskCompletedEvent(executionTask.getId, taskResponse))
    info(s"Finished to mark executionTask(${executionTask.getId}) to  Completed.")
  }

  override def onEventError(event: Event, t: Throwable): Unit = {}

}

