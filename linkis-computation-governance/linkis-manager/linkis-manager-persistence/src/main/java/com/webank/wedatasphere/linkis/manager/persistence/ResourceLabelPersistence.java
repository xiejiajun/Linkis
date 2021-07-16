package com.webank.wedatasphere.linkis.manager.persistence;

import com.webank.wedatasphere.linkis.manager.common.entity.label.LabelKeyValue;
import com.webank.wedatasphere.linkis.manager.common.entity.persistence.PersistenceLabel;
import com.webank.wedatasphere.linkis.manager.common.entity.persistence.PersistenceResource;
import com.webank.wedatasphere.linkis.manager.label.entity.Label;

import java.util.List;
import java.util.Map;


public interface ResourceLabelPersistence {


    /**
     * 拿到的Label一定要在 Label_resource 表中有记录
     * labelKeyValues 是由多个Label打散好放到List中，方便存在重复的Key
     * 1. 只要labelValueSize达到要求就返回
     * 2. 返回的Label一定要在labelResource中有记录,也就是外面还得有一层Join
     *
     * @return
     */
    List<PersistenceLabel> getResourceLabels(List<LabelKeyValue> labelKeyValues);


    /**
     * 拿到的Label一定要在 Label_resource 表中有记录
     * 返回的Label一定要在labelResource中有记录,也就是外面还得有一层Join
     *
     * @return
     */
    List<PersistenceLabel> getResourceLabels(Map<String, Map<String, String>> labelKeyAndValuesMap, Label.ValueRelation valueRelation);

    /**
     * 判断ID是否存在，如果不存在则先插入，在插入关联关系
     *
     * @param label
     * @param persistenceResource
     */
    void setResourceToLabel(PersistenceLabel label, PersistenceResource persistenceResource);


    /**
     * 获取Resource 通过Label
     *
     * @param label
     * @return
     */
    List<PersistenceResource> getResourceByLabel(PersistenceLabel label);

    /**
     * 删除label和resource的关联关系
     *
     * @param label
     */
    void removeResourceByLabel(PersistenceLabel label);


    /**
     * @param labels
     */
    void removeResourceByLabels(List<PersistenceLabel> labels);


}
