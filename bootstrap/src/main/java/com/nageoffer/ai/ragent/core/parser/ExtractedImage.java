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

package com.nageoffer.ai.ragent.core.parser;

import java.util.Arrays;
import java.util.Objects;

/**
 * 从文档中提取的嵌入图片
 *
 * @param index    图片在文档中的序号（从0开始）
 * @param data     图片的二进制数据
 * @param mimeType 图片的 MIME 类型（如 image/png、image/jpeg）
 */
public record ExtractedImage(int index, byte[] data, String mimeType) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExtractedImage that)) return false;
        return index == that.index
                && Arrays.equals(data, that.data)
                && Objects.equals(mimeType, that.mimeType);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(index);
        result = 31 * result + Arrays.hashCode(data);
        result = 31 * result + Objects.hashCode(mimeType);
        return result;
    }
}
