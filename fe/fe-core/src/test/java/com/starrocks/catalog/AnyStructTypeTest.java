// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.catalog;

import com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Test;

public class AnyStructTypeTest {
    @Test
    public void testTypeMatch() {
        Assert.assertTrue(Type.ANY_STRUCT.matchesType(Type.ANY_STRUCT));
        Assert.assertTrue(Type.ANY_STRUCT.matchesType(Type.ANY_ELEMENT));
        StructType structType = new StructType(Lists.newArrayList(Type.BIGINT, Type.DOUBLE));
        Assert.assertTrue(Type.ANY_STRUCT.matchesType(structType));
    }
}
