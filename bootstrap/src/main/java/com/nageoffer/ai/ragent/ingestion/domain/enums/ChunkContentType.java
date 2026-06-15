/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.ingestion.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识块内容类型枚举
 * <p>
 * 区分文本块和图片块，用于 embedding、检索和持久化。
 * 数据库列保持 VARCHAR 类型，通过 MyBatis Plus @EnumValue 做映射。
 */
@Getter
@AllArgsConstructor
public enum ChunkContentType {

    TEXT("TEXT"),
    IMAGE("IMAGE");

    private final String value;

    /**
     * 从字符串值安全解析枚举，无法匹配时返回 {@link #TEXT}
     *
     * @param value 字符串值（如 "TEXT"、"IMAGE"）
     * @return 对应的枚举实例
     */
    public static ChunkContentType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        for (ChunkContentType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return TEXT;
    }
}
