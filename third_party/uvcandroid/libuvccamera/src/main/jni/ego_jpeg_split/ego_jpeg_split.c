#include <jni.h>
#include "jpeg_split_core.h"
JNIEXPORT jobjectArray JNICALL
Java_com_egouvc_phone_export_LosslessJpegSplit_split(JNIEnv *env, jclass cls,
    jbyteArray input, jint width, jint height) {
    (void)cls;
    char message[512] = {0};
    unsigned char *out[2] = {NULL,NULL};
    unsigned long sizes[2] = {0,0};
    jobjectArray result = NULL;
    if(!input) {
        jclass ex=(*env)->FindClass(env,"java/io/IOException");
        if(ex)(*env)->ThrowNew(env,ex,"Missing JPEG input");
        return NULL;
    }
    jbyte *raw=(*env)->GetByteArrayElements(env,input,NULL);
    if(!raw)return NULL;
    int status=ego_split_jpeg((unsigned char *)raw,
        (unsigned long)(*env)->GetArrayLength(env,input),width,height,out,sizes,message);
    (*env)->ReleaseByteArrayElements(env,input,raw,JNI_ABORT);
    if(status<0) {
        jclass ex=(*env)->FindClass(env,status==-2
            ? "com/egouvc/phone/export/LosslessJpegSplit$CorruptFrameException"
            : "java/io/IOException");
        if(ex)(*env)->ThrowNew(env,ex,message);
        return NULL;
    }
    jclass array_class=(*env)->FindClass(env,"[B");
    if(!array_class)goto cleanup;
    result=(*env)->NewObjectArray(env,2,array_class,NULL);
    (*env)->DeleteLocalRef(env,array_class);
    if(!result)goto cleanup;
    for(int side=0;side<2;++side) {
        if(sizes[side]==0 || sizes[side]>0x7fffffffUL) {
            jclass ex=(*env)->FindClass(env,"java/io/IOException");
            if(ex)(*env)->ThrowNew(env,ex,"Invalid cropped JPEG size");
            result=NULL;goto cleanup;
        }
        jbyteArray item=(*env)->NewByteArray(env,(jsize)sizes[side]);
        if(!item)goto cleanup;
        (*env)->SetByteArrayRegion(env,item,0,(jsize)sizes[side],(jbyte *)out[side]);
        if(!(*env)->ExceptionCheck(env))(*env)->SetObjectArrayElement(env,result,side,item);
        (*env)->DeleteLocalRef(env,item);
        if((*env)->ExceptionCheck(env))goto cleanup;
    }
cleanup:
    for(int side=0;side<2;++side)ego_free_jpeg(out[side]);
    return result;
}
