package com.google.android.cameraview.gles;

import java.util.HashMap;

/**
 * Common base class for EGL surfaces.
 * <p>
 * There can be multiple surfaces associated with a single context.
 */

public interface Base64Callback {
    void onResponse(HashMap<String, String> dictionary); // Params are self-defined and added to suit your needs.
}
