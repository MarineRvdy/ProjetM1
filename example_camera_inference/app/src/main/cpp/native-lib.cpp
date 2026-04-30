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
#include "vector"
#include "edge-impulse-sdk/classifier/ei_run_classifier.h"
#include "edge-impulse-sdk/dsp/image/image.hpp"

jbyte* byteData = nullptr;
#define CAMERA_INPUT_WIDTH 640
#define CAMERA_INPUT_HEIGHT 480
#define PIXEL_NUM 3

// Variables globales pour les ratios d'écran (plus utilisées)
// float g_x_ratio = 1080.0f / (float)EI_CLASSIFIER_INPUT_WIDTH;
// float g_y_ratio = 2400.0f / (float)EI_CLASSIFIER_INPUT_HEIGHT;

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
        jbyteArray image_data) {

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
                                                   "(Ljava/util/Map;Ljava/util/List;Lcom/example/test_camera/Timing;)V");

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
        
        // Transmettre les bounding boxes brutes du modèle (320x320) sans scaling
        float x = (float)bb.x;
        float y = (float)bb.y;
        float width = (float)bb.width;
        float height = (float)bb.height;

        __android_log_print(ANDROID_LOG_INFO, "MAIN", "BB raw: x=%.2f, y=%.2f, w=%.2f, h=%.2f (model: %dx%d)", 
                            x, y, width, height, EI_CLASSIFIER_INPUT_WIDTH, EI_CLASSIFIER_INPUT_HEIGHT);
        
        // Log détaillé pour debug FOMO - vérifier le format des coordonnées
        __android_log_print(ANDROID_LOG_INFO, "FOMO_DEBUG", "Label: %s, Confidence: %.3f, Coords: (%.2f,%.2f,%.2f,%.2f)", 
                            bb.label, bb.value, x, y, width, height);
        
        // Vérifier si les coordonnées semblent être en format grille (petites valeurs) ou déjà à l'échelle
        bool isGridFormat = (x < 20.0f && y < 20.0f && width < 20.0f && height < 20.0f);
        __android_log_print(ANDROID_LOG_INFO, "FOMO_DEBUG", "Format grille: %s", isGridFormat ? "OUI" : "NON");

        jstring label = env->NewStringUTF(bb.label);
        jobject boundingBoxObj = env->NewObject(boundingBoxClass,
                                                boundingBoxConstructor,
                                                label,
                                                (jfloat)bb.value,
                                                (jint)x,
                                                (jint)y,
                                                (jint)width,
                                                (jint)height);
        env->CallBooleanMethod(boundingBoxList, listAdd, boundingBoxObj);

        env->DeleteLocalRef(label);
        env->DeleteLocalRef(boundingBoxObj);
    }
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
                                             timingObject);

    return inferenceResult;
}

// Fonction de mise à jour des ratios d'écran (plus utilisée)
extern "C" JNIEXPORT void JNICALL
Java_com_example_test_1camera_MainActivity_updateScreenRatios(
        JNIEnv* env,
        jobject,
        jint screenWidth,
        jint screenHeight) {
    
    // Plus nécessaire : le scaling est maintenant géré entièrement en Kotlin
    __android_log_print(ANDROID_LOG_INFO, "MAIN", "updateScreenRatios: scaling now handled in Kotlin");
}