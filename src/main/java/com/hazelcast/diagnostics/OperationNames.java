package com.hazelcast.diagnostics;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps fully qualified operation class names (as written by the Invocation/Operation profiler plugins)
 * to short display names. The simple class name is used unless it is ambiguous, in which case the
 * fully qualified name is kept so that distinct operations are never merged.
 */
final class OperationNames {

    private OperationNames() {
    }

    static String simpleName(String fullName) {
        return fullName.substring(fullName.lastIndexOf('.') + 1);
    }

    static Map<String, String> displayNames(Collection<String> fullNames) {
        Map<String, Integer> simpleNameCount = new HashMap<>();
        for (String fullName : fullNames) {
            simpleNameCount.merge(simpleName(fullName), 1, Integer::sum);
        }
        Map<String, String> result = new HashMap<>();
        for (String fullName : fullNames) {
            String simpleName = simpleName(fullName);
            result.put(fullName, simpleNameCount.get(simpleName) > 1 ? fullName : simpleName);
        }
        return result;
    }

    static boolean hasSamples(Map<Long, Long> latencyProfile) {
        for (long count : latencyProfile.values()) {
            if (count > 0) {
                return true;
            }
        }
        return false;
    }
}

