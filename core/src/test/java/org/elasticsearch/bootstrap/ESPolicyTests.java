/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.bootstrap;

import org.elasticsearch.test.ESTestCase;

import java.io.FilePermission;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collections;

/** 
 * Tests for ESPolicy
 * <p>
 * Most unit tests won't run under security manager, since we don't allow 
 * access to the policy (you cannot construct it)
 */
public class ESPolicyTests extends ESTestCase {

    /** 
     * Test policy with null codesource.
     * <p>
     * This can happen when restricting privileges with doPrivileged,
     * even though ProtectionDomain's ctor javadocs might make you think
     * that the policy won't be consulted.
     */
    public void testNullCodeSource() throws Exception {
        assumeTrue("test cannot run with security manager", System.getSecurityManager() == null);
        // create a policy with AllPermission
        Permission all = new AllPermission();
        PermissionCollection allCollection = all.newPermissionCollection();
        allCollection.add(all);
        ESPolicy policy = new ESPolicy(allCollection, Collections.<String,Policy>emptyMap(), true);
        // restrict ourselves to NoPermission
        PermissionCollection noPermissions = new Permissions();
        assertFalse(policy.implies(new ProtectionDomain(null, noPermissions), new FilePermission("foo", "read")));
    }

    /** 
     * test with null location
     * <p>
     * its unclear when/if this happens, see https://bugs.openjdk.java.net/browse/JDK-8129972
     */
    public void testNullLocation() throws Exception {
        assumeTrue("test cannot run with security manager", System.getSecurityManager() == null);
        PermissionCollection noPermissions = new Permissions();
        ESPolicy policy = new ESPolicy(noPermissions, Collections.<String,Policy>emptyMap(), true);
        assertFalse(policy.implies(new ProtectionDomain(new CodeSource(null, (Certificate[])null), noPermissions), new FilePermission("foo", "read")));
    }

    /** 
     * test restricting privileges to no permissions actually works
     */
    public void testRestrictPrivileges() {
        assumeTrue("test requires security manager", System.getSecurityManager() != null);
        try {
            System.getProperty("user.home");
        } catch (SecurityException e) {
            fail("this test needs to be fixed: user.home not available by policy");
        }

        PermissionCollection noPermissions = new Permissions();
        AccessControlContext noPermissionsAcc = new AccessControlContext(
            new ProtectionDomain[] {
                new ProtectionDomain(null, noPermissions)
            }
        );
        try {
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                public Void run() {
                    System.getProperty("user.home");
                    fail("access should have been denied");
                    return null;
                }
            }, noPermissionsAcc);
        } catch (SecurityException expected) {
            // expected exception
        }
    }
}
