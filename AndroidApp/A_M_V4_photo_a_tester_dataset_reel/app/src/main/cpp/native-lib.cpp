/* The Clear BSD License
 *
 * Copyright (c) 2025 EdgeImpulse Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted (subject to the limitations in the disclaimer
 * below) provided that the following conditions are met:
 *
 *   * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 *   * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 *   * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from this
 *   software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE GRANTED BY
 * THIS LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <stdio.h>
#include <cmath>
#include "vector"
#include "edge-impulse-sdk/classifier/ei_run_classifier.h"
#include "edge-impulse-sdk/dsp/image/image.hpp"

jbyte* byteData = nullptr;
#define CAMERA_INPUT_WIDTH 480
#define CAMERA_INPUT_HEIGHT 640
#define PIXEL_NUM 3

static int ei_camera_get_data(size_t offset, size_t length, float *out_ptr)
{
    // we already have a RGB888 buffer, so recalculate offset into pixel index
    size_t pixel_ix = offset * 3;
    size_t pixels_left = length;
    size_t out_ptr_ix = 0;

    while (pixels_left != 0) {

        uint8_t r = static_cast<uint8_t>(byteData[pixel_ix]);
        uint8_t g = static_cast<uint8_t>(byteData[pixel_ix + 1]);
        uint8_t b = static_cast<uint8_t>(byteData[pixel_ix + 2]);

        out_ptr[out_ptr_ix] = (r << 16) + (g << 8) + b;

        // go to the next pixel
        out_ptr_ix++;
        pixel_ix+=3;
        pixels_left--;
    }

    // and done!
    return 0;
}

extern "C" JNIEXPORT jobject JNICALL

Java_com_example_test_1camera_MainActivity_passToCpp(
        JNIEnv* env,
        jobject,
        jbyteArray image_data,
        jint preview_width,
        jint preview_height,
        jboolean is_portrait) {

    // Get byte array data from JNI
    byteData = env->GetByteArrayElements(image_data, nullptr);
    jsize byteArrayLength = env->GetArrayLength(image_data);

    if (byteArrayLength != CAMERA_INPUT_WIDTH * CAMERA_INPUT_HEIGHT * PIXEL_NUM) {
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "The size of your 'features' array is not correct. Expected %d items, but had %d\n",
                            CAMERA_INPUT_WIDTH * CAMERA_INPUT_HEIGHT * PIXEL_NUM, byteArrayLength);
        return nullptr;
    }

    ei::image::processing::crop_and_interpolate_rgb888(
            (uint8_t*)byteData,
            CAMERA_INPUT_WIDTH,
            CAMERA_INPUT_HEIGHT,
            (uint8_t*)byteData,
            EI_CLASSIFIER_INPUT_WIDTH,
            EI_CLASSIFIER_INPUT_HEIGHT);

    ei_impulse_result_t result;

    signal_t signal;
    signal.total_length = EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT;
    signal.get_data = &ei_camera_get_data;

    EI_IMPULSE_ERROR res = run_classifier(&signal, &result, false);

    // Find Java classes
    jclass resultClass = env->FindClass("com/example/test_camera/InferenceResult");
    jclass timingClass = env->FindClass("com/example/test_camera/Timing");
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jclass listClass = env->FindClass("java/util/ArrayList");
    jclass boundingBoxClass = env->FindClass("com/example/test_camera/BoundingBox");

    if (!resultClass || !timingClass || !hashMapClass || !listClass || !boundingBoxClass) {
        return nullptr; // Error finding classes
    }

    // Get method IDs
    jmethodID resultConstructor = env->GetMethodID(resultClass, "<init>",
                                                   "(Ljava/util/Map;Ljava/util/List;Ljava/util/List;Ljava/util/Map;Lcom/example/test_camera/Timing;)V");

    jmethodID boundingBoxConstructor = env->GetMethodID(boundingBoxClass, "<init>",
                                                        "(Ljava/lang/String;FIIII)V");

    jmethodID timingConstructor = env->GetMethodID(timingClass, "<init>",
                                                   "(IIIIJJJ)V");

    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID hashMapPut = env->GetMethodID(hashMapClass, "put",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jmethodID listAdd = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");

    jclass floatClass = env->FindClass("java/lang/Float");
    if (!floatClass) return nullptr;

    // Get Float constructor method ID
    jmethodID floatConstructor = env->GetMethodID(floatClass, "<init>", "(F)V");
    if (!floatConstructor) return nullptr; // Error finding constructor

#if EI_CLASSIFIER_LABEL_COUNT > 0
    // Construct classification map
    jobject classificationMap = env->NewObject(hashMapClass, hashMapInit);
    for (size_t i = 0; i < EI_CLASSIFIER_LABEL_COUNT; i++) {
        jstring key = env->NewStringUTF(result.classification[i].label);
        jobject value = env->NewObject(floatClass, floatConstructor, result.classification[i].value);

        env->CallObjectMethod(classificationMap, hashMapPut, key, value);

        // Cleanup local references
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
#endif

#if EI_CLASSIFIER_OBJECT_DETECTION == 1
    // Create ArrayList for object detections
    jobject boundingBoxList = env->NewObject(listClass, env->GetMethodID(listClass, "<init>", "()V"));
    for (uint32_t i = 0; i < result.bounding_boxes_count; i++) {
        ei_impulse_result_bounding_box_t bb = result.bounding_boxes[i];
        if (bb.value == 0) continue;
        
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Raw BB data: value=%.3f, x=%.3f, y=%.3f, w=%.3f, h=%.3f", 
                     bb.value, bb.x, bb.y, bb.width, bb.height);
        
        // Check memory addresses and raw bytes
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Memory check: &bb=%p, &bb.width=%p, &bb.height=%p", 
                     &bb, &bb.width, &bb.height);
        
        // Force integer comparison to avoid floating point issues
        uint32_t width_raw = *((uint32_t*)&bb.width);
        uint32_t height_raw = *((uint32_t*)&bb.height);
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Raw bytes: width=0x%08x, height=0x%08x", width_raw, height_raw);
        
        // TEMPORAIRE: Désactiver le filtrage pour voir toutes les détections
        // Skip if raw bytes indicate zero or invalid values
        // 0x00000008 = very small float (~1e-38), treat as zero
        if (false) { // width_raw == 0 || height_raw == 0 || width_raw == 0x80000000 || height_raw == 0x80000000 || 
            //width_raw == 0x00000008 || height_raw == 0x00000008) {
            __android_log_print(ANDROID_LOG_INFO, "MAIN", "Skipping BB based on raw bytes: 0x%08x, 0x%08x", width_raw, height_raw);
            continue;
        }
        
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Valid BB: x=%.1f, y=%.1f, w=%.1f, h=%.1f", bb.x, bb.y, bb.width, bb.height);
        
        // Calculate ratios based on device orientation
        float x_ratio, y_ratio;
        if (is_portrait) {
            // Portrait: image is rotated 90°, so width/height are swapped
            x_ratio = preview_width / (float)EI_CLASSIFIER_INPUT_HEIGHT;  // Use height for x
            y_ratio = preview_height / (float)EI_CLASSIFIER_INPUT_WIDTH; // Use width for y
        } else {
            // Landscape: image is rotated 270°, so width/height are swapped AND axes are inverted
            x_ratio = preview_width / (float)EI_CLASSIFIER_INPUT_HEIGHT;  // Use height for x
            y_ratio = preview_height / (float)EI_CLASSIFIER_INPUT_WIDTH; // Use width for y
        }
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Dimensions: preview=%dx%d, model=%dx%d, ratios=%.2fx%.2f", 
                     preview_width, preview_height, EI_CLASSIFIER_INPUT_WIDTH, EI_CLASSIFIER_INPUT_HEIGHT, x_ratio, y_ratio);
        //__android_log_print(ANDROID_LOG_INFO, "MAIN", "x_ratio: %f, y_ratio: %f", x_ratio, y_ratio);

        float x, y, width, height;
        
        // FOMO models use grid cell coordinates, need to convert to pixels
        // Let's try without conversion first to see if coordinates are already in pixels
        float cell_size = 1.0f; // (float)EI_CLASSIFIER_INPUT_WIDTH / 16.0f; // FOMO typically uses 16x16 grid
        
        if (is_portrait) {
            // Portrait: normal scaling with 90° rotation
            x = (float)bb.x * cell_size * x_ratio;
            y = (float)bb.y * cell_size * y_ratio;
            width = (float)bb.width * cell_size * x_ratio;
            height = (float)bb.height * cell_size * y_ratio;
        } else {
            // Landscape: simpler transformation for 270° rotation
            // The bitmap is already rotated in Kotlin, so just scale coordinates
            x = (float)bb.x * cell_size * x_ratio;
            y = (float)bb.y * cell_size * y_ratio;
            width = (float)bb.width * cell_size * x_ratio;
            height = (float)bb.height * cell_size * y_ratio;
        }

        //__android_log_print(ANDROID_LOG_INFO, "MAIN", "bb: x=%f, y=%f, w=%f, h=%f", bb.x, bb.y, bb.width, bb.height);
        //__android_log_print(ANDROID_LOG_INFO, "MAIN", "transformed: x=%f, y=%f, w=%f, h=%f", x, y, width, height);

        jstring label = env->NewStringUTF(bb.label);
        jobject boundingBoxObj = env->NewObject(boundingBoxClass,
                                                boundingBoxConstructor,
                                                label,
                                                (jfloat)bb.value,
                                                (jint)round(x),
                                                (jint)round(y),
                                                (jint)round(width),
                                                (jint)round(height));
        env->CallBooleanMethod(boundingBoxList, listAdd, boundingBoxObj);

        env->DeleteLocalRef(label);
        env->DeleteLocalRef(boundingBoxObj);
    }
#endif

    // Create HashMap for anomaly values
    jobject anomalyResultMap = env->NewObject(hashMapClass, hashMapInit);

#if EI_CLASSIFIER_HAS_ANOMALY != 3
    jobject anomalyString = env->NewStringUTF("anomaly");
    jobject anomalyValue = env->NewObject(floatClass, floatConstructor, (jfloat)result.anomaly);

    env->CallObjectMethod(anomalyResultMap, hashMapPut, anomalyString, anomalyValue);
    env->DeleteLocalRef(anomalyString);
    env->DeleteLocalRef(anomalyValue);
#endif

#if EI_CLASSIFIER_HAS_VISUAL_ANOMALY
    // Create ArrayList for visual anomaly grid cells
    jobject boundingBoxListAnomaly = env->NewObject(listClass, env->GetMethodID(listClass, "<init>", "()V"));
    for (uint32_t i = 0; i < result.visual_ad_count; i++) {
        ei_impulse_result_bounding_box_t bb = result.visual_ad_grid_cells[i];

        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Raw BB data: value=%.3f, x=%.3f, y=%.3f, w=%.3f, h=%.3f", 
                     bb.value, bb.x, bb.y, bb.width, bb.height);
        
        // Check memory addresses and raw bytes
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Memory check: &bb=%p, &bb.width=%p, &bb.height=%p", 
                     &bb, &bb.width, &bb.height);
        
        // Force integer comparison to avoid floating point issues
        uint32_t width_raw = *((uint32_t*)&bb.width);
        uint32_t height_raw = *((uint32_t*)&bb.height);
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Raw bytes: width=0x%08x, height=0x%08x", width_raw, height_raw);
        
        // TEMPORAIRE: Désactiver le filtrage pour voir toutes les détections
        // Skip if raw bytes indicate zero or invalid values
        // 0x00000008 = very small float (~1e-38), treat as zero
        if (false) { // width_raw == 0 || height_raw == 0 || width_raw == 0x80000000 || height_raw == 0x80000000 || 
            //width_raw == 0x00000008 || height_raw == 0x00000008) {
            __android_log_print(ANDROID_LOG_INFO, "MAIN", "Skipping BB based on raw bytes: 0x%08x, 0x%08x", width_raw, height_raw);
            continue;
        }
        
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Valid BB: x=%.1f, y=%.1f, w=%.1f, h=%.1f", bb.x, bb.y, bb.width, bb.height);
        
        // Calculate ratios based on device orientation
        float x_ratio, y_ratio;
        if (is_portrait) {
            // Portrait: image is rotated 90°, so width/height are swapped
            x_ratio = preview_width / (float)EI_CLASSIFIER_INPUT_HEIGHT;  // Use height for x
            y_ratio = preview_height / (float)EI_CLASSIFIER_INPUT_WIDTH; // Use width for y
        } else {
            // Landscape: image is rotated 270°, so width/height are swapped AND axes are inverted
            x_ratio = preview_width / (float)EI_CLASSIFIER_INPUT_HEIGHT;  // Use height for x
            y_ratio = preview_height / (float)EI_CLASSIFIER_INPUT_WIDTH; // Use width for y
        }
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "Dimensions: preview=%dx%d, model=%dx%d, ratios=%.2fx%.2f", 
                     preview_width, preview_height, EI_CLASSIFIER_INPUT_WIDTH, EI_CLASSIFIER_INPUT_HEIGHT, x_ratio, y_ratio);
        //__android_log_print(ANDROID_LOG_INFO, "MAIN", "x_ratio: %f, y_ratio: %f", x_ratio, y_ratio);

        float x, y, width, height;
        
        // FOMO models use grid cell coordinates, need to convert to pixels
        // Let's try without conversion first to see if coordinates are already in pixels
        float cell_size = 1.0f; // (float)EI_CLASSIFIER_INPUT_WIDTH / 16.0f; // FOMO typically uses 16x16 grid
        
        if (is_portrait) {
            // Portrait: normal scaling with 90° rotation
            x = (float)bb.x * cell_size * x_ratio;
            y = (float)bb.y * cell_size * y_ratio;
            width = (float)bb.width * cell_size * x_ratio;
            height = (float)bb.height * cell_size * y_ratio;
        } else {
            // Landscape: simpler transformation for 270° rotation
            // The bitmap is already rotated in Kotlin, so just scale coordinates
            x = (float)bb.x * cell_size * x_ratio;
            y = (float)bb.y * cell_size * y_ratio;
            width = (float)bb.width * cell_size * x_ratio;
            height = (float)bb.height * cell_size * y_ratio;
        }

        jstring label = env->NewStringUTF("anomaly");
        jobject boundingBoxObj = env->NewObject(boundingBoxClass,
                                                boundingBoxConstructor,
                                                label,
                                                (jfloat)bb.value,
                                                (jint)round(x),
                                                (jint)round(y),
                                                (jint)round(width),
                                                (jint)round(height));
        env->CallBooleanMethod(boundingBoxListAnomaly, listAdd, boundingBoxObj);

        env->DeleteLocalRef(label);
        env->DeleteLocalRef(boundingBoxObj);
    }

    jobject maxString = env->NewStringUTF("max");
    jobject maxValue = env->NewObject(floatClass, floatConstructor, (jfloat)result.visual_ad_result.max_value);

    jobject meanString = env->NewStringUTF("mean");
    jobject meanValue = env->NewObject(floatClass, floatConstructor, (jfloat)result.visual_ad_result.mean_value);

    env->CallObjectMethod(anomalyResultMap, hashMapPut, maxString, maxValue);
    env->CallObjectMethod(anomalyResultMap, hashMapPut, meanString, meanValue);
    env->DeleteLocalRef(meanString);
    env->DeleteLocalRef(maxString);
    env->DeleteLocalRef(meanValue);
    env->DeleteLocalRef(maxValue);
#endif

    // Construct Timing object
    jobject timingObject = env->NewObject(timingClass, timingConstructor,
                                          result.timing.sampling,
                                          result.timing.dsp,
                                          result.timing.classification,
                                          result.timing.anomaly,
                                          result.timing.dsp_us,
                                          result.timing.classification_us,
                                          result.timing.anomaly_us);

    // Construct InferenceResult object
    jobject inferenceResult = env->NewObject(resultClass,
                                             resultConstructor,
#if EI_CLASSIFIER_LABEL_COUNT > 0
                                             classificationMap,
#else
            nullptr,
#endif
#if EI_CLASSIFIER_OBJECT_DETECTION == 1
            boundingBoxList,
#else
                                             nullptr,
#endif
#if EI_CLASSIFIER_HAS_VISUAL_ANOMALY
            boundingBoxListAnomaly,
#else
                                             nullptr,
#endif
#if EI_CLASSIFIER_HAS_ANOMALY
            anomalyResultMap,
#else
                                             nullptr,
#endif
                                             timingObject);

    return inferenceResult;
}