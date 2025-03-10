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


package com.starrocks.sql.analyzer;

import com.starrocks.catalog.Database;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.CreateViewStmt;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class AnalyzeUtilTest {
    @BeforeClass
    public static void beforeClass() throws Exception {
        AnalyzeTestUtil.init();
    }

    @Test
    public void testSubQuery() throws Exception {
        String sql;
        sql = "select count(*) from (select v1 from t0 group by v1) tx";
        List<StatementBase> statementBase =
                SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Map<String, Database> stringDatabaseMap =
                AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assert.assertEquals(1, stringDatabaseMap.size());
        sql = "select count(*) from (select * from tarray, unnest(v3))";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assert.assertEquals(1, stringDatabaseMap.size());
        sql = "with mview as (select count(*) from t0) select * from mview";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assert.assertEquals(1, stringDatabaseMap.size());
        // test view
        String viewTestDB = "view_test";
        AnalyzeTestUtil.getStarRocksAssert().withDatabase(viewTestDB).useDatabase(viewTestDB);
        sql = "create view basic as select v1 from test.t0;";
        CreateViewStmt createTableStmt =
                (CreateViewStmt) UtFrameUtils.parseStmtWithNewParser(sql, AnalyzeTestUtil.getConnectContext());
        GlobalStateMgr.getCurrentState().createView(createTableStmt);
        sql = "select v1 from basic";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        final ConnectContext session = AnalyzeTestUtil.getConnectContext();
        com.starrocks.sql.analyzer.Analyzer.analyze(statementBase.get(0), session);
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assert.assertEquals(stringDatabaseMap.size(), 2);

        sql = "insert into test.t0 select * from db1.t0,db2.t1";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assert.assertEquals(stringDatabaseMap.size(), 3);
        Assert.assertEquals("[db1, test, db2]", stringDatabaseMap.keySet().toString());

        sql = "update test.t0 set v1 = 1";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assert.assertEquals(stringDatabaseMap.size(), 1);
        Assert.assertEquals("[test]", stringDatabaseMap.keySet().toString());

        sql = "delete from test.t0 where v1 = 1";
        statementBase = SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        stringDatabaseMap = AnalyzerUtils.collectAllDatabase(AnalyzeTestUtil.getConnectContext(), statementBase.get(0));
        Assert.assertEquals(stringDatabaseMap.size(), 1);
        Assert.assertEquals("[test]", stringDatabaseMap.keySet().toString());
    }
}
