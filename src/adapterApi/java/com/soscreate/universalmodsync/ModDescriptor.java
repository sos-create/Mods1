package com.soscreate.universalmodsync;

import java.util.List;

/** Compile-time copy of the metadata record supplied by UniversalModSync. */
public record ModDescriptor(
        String fileName,
        String loader,
        String modId,
        String version,
        List<String> dependencies,
        String environment,
        String compatibility
) { }
