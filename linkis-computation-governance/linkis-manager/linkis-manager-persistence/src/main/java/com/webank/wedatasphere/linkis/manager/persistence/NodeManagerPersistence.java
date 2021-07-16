package com.webank.wedatasphere.linkis.manager.persistence;

import com.webank.wedatasphere.linkis.common.ServiceInstance;
import com.webank.wedatasphere.linkis.manager.common.entity.node.EngineNode;
import com.webank.wedatasphere.linkis.manager.common.entity.node.Node;
import com.webank.wedatasphere.linkis.manager.exception.PersistenceErrorException;

import java.util.List;


public interface NodeManagerPersistence {

    /**
     * 保存node
     *
     * @param node
     * @throws PersistenceErrorException
     */
    void addNodeInstance(Node node) throws PersistenceErrorException;

    void updateEngineNode(ServiceInstance serviceInstance, Node node) throws PersistenceErrorException;

    /**
     * 移除node
     *
     * @param node
     * @throws PersistenceErrorException
     */
    void removeNodeInstance(Node node) throws PersistenceErrorException;

    /**
     * 根据 owner 获取node列表
     *
     * @param owner
     * @return
     * @throws PersistenceErrorException
     */
    List<Node> getNodes(String owner) throws PersistenceErrorException;


    /**
     * 获取所有node列表
     *
     * @return
     * @throws PersistenceErrorException
     */
    List<Node> getAllNodes() throws PersistenceErrorException;

    /**
     * 更新node信息
     *
     * @param node
     * @throws PersistenceErrorException
     */
    void updateNodeInstance(Node node) throws PersistenceErrorException;

    /**
     * 根据 servericeinstance 获取 Node
     * @param serviceInstance
     * @return
     * @throws PersistenceErrorException
     */
    Node getNode(ServiceInstance serviceInstance) throws PersistenceErrorException;

    /**
     * 1. 插入Engine
     * 2. 插入Engine和EM关系
     *
     * @param engineNode
     * @throws PersistenceErrorException
     */
    void addEngineNode(EngineNode engineNode) throws PersistenceErrorException;

    /**
     * 1. 删除Engine和Em关系，以及清理和Engine相关的metrics信息
     * 2. 删除Engine本身
     *
     * @param engineNode
     * @throws PersistenceErrorException
     */
    void deleteEngineNode(EngineNode engineNode) throws PersistenceErrorException;

    /**
     * 1. 通过Engine的ServiceInstance，获取Engine的信息和EM信息
     *
     * @param serviceInstance
     * @return
     * @throws PersistenceErrorException
     */
    EngineNode getEngineNode(ServiceInstance serviceInstance) throws PersistenceErrorException;

    /**
     * 通过Em的ServiceInstance 获取EM下面Engine的列表
     *
     * @param serviceInstance
     * @return
     * @throws PersistenceErrorException
     */
    List<EngineNode> getEngineNodeByEM(ServiceInstance serviceInstance) throws PersistenceErrorException;
}
