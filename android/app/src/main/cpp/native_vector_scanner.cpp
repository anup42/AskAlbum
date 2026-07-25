#include <jni.h>
#include <cstdint>
#include <cstring>
#include <vector>

#if AGENTICGALLERY_ARM64_FP16
#include <arm_neon.h>
#endif

namespace {

float half_to_float(uint16_t value) {
    const uint32_t sign = static_cast<uint32_t>(value & 0x8000u) << 16u;
    uint32_t exponent = (value >> 10u) & 0x1fu;
    uint32_t fraction = value & 0x03ffu;
    uint32_t bits;
    if (exponent == 0 && fraction == 0) {
        bits = sign;
    } else if (exponent == 0) {
        int shift = 0;
        while ((fraction & 0x0400u) == 0) {
            fraction <<= 1u;
            ++shift;
        }
        fraction &= 0x03ffu;
        bits = sign | ((113u - static_cast<uint32_t>(shift)) << 23u) | (fraction << 13u);
    } else if (exponent == 0x1fu) {
        bits = sign | 0x7f800000u | (fraction << 13u);
    } else {
        bits = sign | ((exponent + 112u) << 23u) | (fraction << 13u);
    }
    float result;
    std::memcpy(&result, &bits, sizeof(result));
    return result;
}

float dot_row(const uint16_t* row, const float* query, int dimension) {
#if AGENTICGALLERY_ARM64_FP16
    float32x4_t sum_low = vdupq_n_f32(0.0f);
    float32x4_t sum_high = vdupq_n_f32(0.0f);
    int index = 0;
    for (; index + 8 <= dimension; index += 8) {
        const float16x8_t half_values = vld1q_f16(reinterpret_cast<const float16_t*>(row + index));
        const float32x4_t values_low = vcvt_f32_f16(vget_low_f16(half_values));
        const float32x4_t values_high = vcvt_f32_f16(vget_high_f16(half_values));
        sum_low = vfmaq_f32(sum_low, values_low, vld1q_f32(query + index));
        sum_high = vfmaq_f32(sum_high, values_high, vld1q_f32(query + index + 4));
    }
    float score = vaddvq_f32(vaddq_f32(sum_low, sum_high));
    for (; index < dimension; ++index) score += half_to_float(row[index]) * query[index];
    return score;
#else
    double score = 0.0;
    for (int index = 0; index < dimension; ++index) {
        score += static_cast<double>(half_to_float(row[index])) * static_cast<double>(query[index]);
    }
    return static_cast<float>(score);
#endif
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_samsung_agenticgallery_NativeVectorScanner_dotFp16Matrix(
        JNIEnv* env,
        jobject,
        jobject buffer,
        jlong vector_offset,
        jint dimension,
        jintArray row_indices,
        jfloatArray query_array) {
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || vector_offset < 0 || dimension <= 0 || query_array == nullptr || row_indices == nullptr) {
        return nullptr;
    }
    const jsize query_length = env->GetArrayLength(query_array);
    const jsize row_count = env->GetArrayLength(row_indices);
    if (query_length != dimension) return nullptr;

    jboolean query_copy = JNI_FALSE;
    jboolean rows_copy = JNI_FALSE;
    jfloat* query = env->GetFloatArrayElements(query_array, &query_copy);
    jint* rows = env->GetIntArrayElements(row_indices, &rows_copy);
    if (query == nullptr || rows == nullptr) {
        if (query != nullptr) env->ReleaseFloatArrayElements(query_array, query, JNI_ABORT);
        if (rows != nullptr) env->ReleaseIntArrayElements(row_indices, rows, JNI_ABORT);
        return nullptr;
    }

    std::vector<float> scores(static_cast<size_t>(row_count));
    bool valid = true;
    for (jsize index = 0; index < row_count; ++index) {
        const jint row = rows[index];
        const jlong offset = vector_offset + static_cast<jlong>(row) * dimension * 2L;
        if (row < 0 || offset < vector_offset || offset + static_cast<jlong>(dimension) * 2L > capacity) {
            valid = false;
            break;
        }
        scores[static_cast<size_t>(index)] = dot_row(
                reinterpret_cast<const uint16_t*>(base + offset), query, dimension);
    }
    env->ReleaseFloatArrayElements(query_array, query, JNI_ABORT);
    env->ReleaseIntArrayElements(row_indices, rows, JNI_ABORT);
    if (!valid) return nullptr;

    jfloatArray output = env->NewFloatArray(row_count);
    if (output != nullptr && row_count > 0) {
        env->SetFloatArrayRegion(output, 0, row_count, scores.data());
    }
    return output;
}
