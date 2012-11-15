/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */


package org.voltdb;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.voltdb.VoltDB.Configuration;
import org.voltdb.client.Client;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.compiler.VoltProjectBuilder;
import org.voltdb.regressionsuites.LocalCluster;
import org.voltdb.utils.MiscUtils;

@RunWith(value = Parameterized.class)
public class TestMidRejoinDeath extends RejoinTestBase {

    @Parameters
    public static Collection<Object[]> useIv2() {
        return Arrays.asList(new Object[][] {{false}, {true}});
    }

    protected final boolean m_useIv2;
    public TestMidRejoinDeath(boolean useIv2)
    {
        m_useIv2 = useIv2 || VoltDB.checkTestEnvForIv2();
    }

    @Test
    public void testMidRejoinDeath() throws Exception {
        LocalCluster cluster = null;
        Client client = null;

        try {
            System.out.println("testMidRejoinDeath");
            VoltProjectBuilder builder = getBuilderForTest();
            builder.setSecurityEnabled(true);

            cluster = new LocalCluster("rejoin.jar", 1, 2, 1,
                    BackendTarget.NATIVE_EE_JNI, false, false);
            cluster.setJavaProperty("rejoindeathtest", null);
            cluster.overrideAnyRequestForValgrind();
            cluster.setMaxHeap(256);
            boolean success = cluster.compile(builder);
            assertTrue(success);
            MiscUtils.copyFile(builder.getPathToDeployment(), Configuration.getPathToCatalogForTest("rejoin.xml"));
            cluster.setHasLocalServer(false);

            cluster.startUp();

            ClientResponse response;

            client = ClientFactory.createClient(m_cconfig);
            client.createConnection("localhost", cluster.port(0));

            response = client.callProcedure("InsertSinglePartition", 0);
            assertEquals(ClientResponse.SUCCESS, response.getStatus());
            response = client.callProcedure("Insert", 1);
            assertEquals(ClientResponse.SUCCESS, response.getStatus());
            response = client.callProcedure("InsertReplicated", 0);
            assertEquals(ClientResponse.SUCCESS, response.getStatus());

            cluster.shutDownSingleHost(1);
            Thread.sleep(3000);

            // try to rejoin, but expect this to fail after 10-15 seconds
            // because the "rejoindeathtest" property is set and that will
            // disable acking of streamed snapshots
            // Note: for IV1, non-pro rejoin should succeed because it doesn't
            // use the same snapshot code.
            cluster.recoverOne(1, 0, "", MiscUtils.isPro());

            // run some random work
            response = client.callProcedure("SelectBlahSinglePartition", 0);
            assertEquals(ClientResponse.SUCCESS, response.getStatus());
            assertEquals(response.getResults()[0].fetchRow(0).getLong(0), 0);

            response = client.callProcedure("SelectBlah", 1);
            assertEquals(ClientResponse.SUCCESS, response.getStatus());
            assertEquals(response.getResults()[0].fetchRow(0).getLong(0), 1);

            response = client.callProcedure("SelectBlahReplicated", 0);
            assertEquals(ClientResponse.SUCCESS, response.getStatus());
            assertEquals(response.getResults()[0].fetchRow(0).getLong(0), 0);

            response = client.callProcedure("InsertSinglePartition", 2);
            assertEquals(ClientResponse.SUCCESS, response.getStatus());
            response = client.callProcedure("Insert", 3);
            assertEquals(ClientResponse.SUCCESS, response.getStatus());
            response = client.callProcedure("InsertReplicated", 1);
            assertEquals(ClientResponse.SUCCESS, response.getStatus());

            // try to snapshot to make sure it still works
            client.callProcedure("@SnapshotSave", "{uripath:\"file:///tmp\",nonce:\"mydb\",block:true,format:\"csv\"}");

            // make sure only one node is running if using snapshot code to rejoin
            // IV1 community uses a different path that doesn't fail
            if (MiscUtils.isPro()) {
                assertEquals(1, cluster.getLiveNodeCount());
            }
        }
        finally {
            if (client != null) client.close();
            if (cluster != null) cluster.shutDown();
        }
    }

}
