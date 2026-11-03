package com.soscreate.universalmodsync;

import java.util.List;

/** Compile-time API stub; binary shape matches UniversalModSync 0.1.0. */
public record ModDescriptor(
        String fileName,
        String loader,
        String modId,
        String version,
        List<String> dependencies,
        String environment,
        String compatibility
) { }
