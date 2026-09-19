package com.mars.cloud.mvc.workthread;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * @since 2025-05-23 16:14
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RequestFacade<T> {

    protected List<T> params;

    public int count() {
        return CollectionUtils.isNotEmpty(params) ? params.size() : 0;
    }
}
