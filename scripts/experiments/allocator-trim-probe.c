/* Diagnostic-only glibc probe for a disposable benchmark worker. */
#include <jni.h>
#include <malloc.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
  (void)vm; (void)reserved;
  if (!options || strlen(options) > 3000) return JNI_ERR;
  char path[4096];
  snprintf(path, sizeof(path), "%s-before.xml", options);
  FILE *before = fopen(path, "wx");
  if (!before) return JNI_ERR;
  int status = malloc_info(0, before);
  if (fclose(before) || status) return JNI_ERR;
  struct timespec start, end;
  clock_gettime(CLOCK_MONOTONIC, &start);
  int trimmed = malloc_trim(0);
  clock_gettime(CLOCK_MONOTONIC, &end);
  snprintf(path, sizeof(path), "%s-after.xml", options);
  FILE *after = fopen(path, "wx");
  if (!after) return JNI_ERR;
  status = malloc_info(0, after);
  if (fclose(after) || status) return JNI_ERR;
  snprintf(path, sizeof(path), "%s-result.txt", options);
  FILE *result = fopen(path, "wx");
  if (!result) return JNI_ERR;
  fprintf(result, "trimmed=%d\nelapsedNs=%lld\n", trimmed,
    (long long)(end.tv_sec-start.tv_sec)*1000000000LL + end.tv_nsec-start.tv_nsec);
  return fclose(result) ? JNI_ERR : JNI_OK;
}
