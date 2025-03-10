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

import com.starrocks.analysis.RedirectStatus;

import java.util.List;

public class SetStmt extends StatementBase {
    private final List<SetVar> setVars;

    public SetStmt(List<SetVar> setVars) {
        this.setVars = setVars;
    }

    public List<SetVar> getSetVars() {
        return setVars;
    }

    @Override
    public boolean needAuditEncryption() {
        for (SetVar var : setVars) {
            if (var instanceof SetPassVar) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RedirectStatus getRedirectStatus() {
        if (setVars != null) {
            for (SetVar var : setVars) {
                if (var instanceof SetPassVar) {
                    return RedirectStatus.FORWARD_WITH_SYNC;
                } else if (var.getType() == SetType.GLOBAL) {
                    return RedirectStatus.FORWARD_WITH_SYNC;
                }
            }
        }
        return RedirectStatus.NO_FORWARD;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitSetStatement(this, context);
    }
}

