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


package com.starrocks.sql.ast;

import com.starrocks.analysis.ParseNode;
import com.starrocks.analysis.UserIdentity;

/**
 * grantRevokeClause
 * : (user | ROLE identifierOrString ) (WITH GRANT OPTION)?
 * ;
 */
public class GrantRevokeClause implements ParseNode {
    private UserIdentity userIdentity;
    private String roleName;
    private boolean withGrantOption;

    public GrantRevokeClause(UserIdentity userIdentifier, String roleName, boolean withGrantOption) {
        this.userIdentity = userIdentifier;
        this.roleName = roleName;
        this.withGrantOption = withGrantOption;
    }

    public UserIdentity getUserIdentity() {
        return userIdentity;
    }

    public String getRoleName() {
        return roleName;
    }

    public boolean isWithGrantOption() {
        return withGrantOption;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }
}
