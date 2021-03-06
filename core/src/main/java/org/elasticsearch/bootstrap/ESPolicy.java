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

import org.elasticsearch.common.SuppressForbidden;

import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.security.URIParameter;

/** custom policy for union of static and dynamic permissions */
final class ESPolicy extends Policy {
    
    /** template policy file, the one used in tests */
    static final String POLICY_RESOURCE = "security.policy";
    
    final Policy template;
    final PermissionCollection dynamic;

    public ESPolicy(PermissionCollection dynamic) throws Exception {
        URI uri = getClass().getResource(POLICY_RESOURCE).toURI();
        this.template = Policy.getInstance("JavaPolicy", new URIParameter(uri));
        this.dynamic = dynamic;
    }

    @Override @SuppressForbidden(reason = "fast equals check is desired")
    public boolean implies(ProtectionDomain domain, Permission permission) {        
        CodeSource codeSource = domain.getCodeSource();
        // codesource can be null when reducing privileges via doPrivileged()
        if (codeSource != null) {
            URL location = codeSource.getLocation();
            // location can be null... ??? nobody knows
            // https://bugs.openjdk.java.net/browse/JDK-8129972
            if (location != null) {
                // run groovy scripts with no permissions
                if ("/groovy/script".equals(location.getFile())) {
                    return false;
                }
            }
        }

        // Special handling for broken AWS code which destroys all SSL security
        // REMOVE THIS when https://github.com/aws/aws-sdk-java/pull/432 is fixed
        if (permission instanceof RuntimePermission && "accessClassInPackage.sun.security.ssl".equals(permission.getName())) {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                if ("com.amazonaws.http.conn.ssl.SdkTLSSocketFactory".equals(element.getClassName()) &&
                      "verifyMasterSecret".equals(element.getMethodName())) {
                    // we found the horrible method: the hack begins!
                    // force the aws code to back down, by throwing an exception that it catches.
                    rethrow(new IllegalAccessException("no amazon, you cannot do this."));
                }
            }
        }
        // otherwise defer to template + dynamic file permissions
        return template.implies(domain, permission) || dynamic.implies(permission);
    }

    /**
     * Classy puzzler to rethrow any checked exception as an unchecked one.
     */
    private static class Rethrower<T extends Throwable> {
        private void rethrow(Throwable t) throws T {
            throw (T) t;
        }
    }

    /**
     * Rethrows <code>t</code> (identical object).
     */
    private void rethrow(Throwable t) {
        new Rethrower<Error>().rethrow(t);
    }
}
