#include <string.h>
#include "turbojpeg.h"
#include "jpeg_split_core.h"
void ego_free_jpeg(unsigned char *jpeg) { if(jpeg)tjFree(jpeg); }
int ego_split_jpeg(const unsigned char *raw, unsigned long length, int expected_width, int expected_height,
                   unsigned char *out[2], unsigned long sizes[2], char error[512]) {
    const char *message = NULL;
    int status = -1;
    tjhandle handle = tjInitTransform();
    int width = 0, height = 0, sampling = -1, colorspace = -1;
    out[0]=out[1]=NULL;sizes[0]=sizes[1]=0;error[0]=0;
    if (!handle) {message="Cannot initialize lossless JPEG transform";goto cleanup;}
    if (!raw || !length) {message="Missing JPEG input";goto cleanup;}
    if (tjDecompressHeader3(handle,raw,length,&width,&height,&sampling,&colorspace)<0) {
        message=tjGetErrorStr2(handle);
        if(tjGetErrorCode(handle)==TJERR_WARNING)status=-2;
        goto cleanup;
    }
    if (width!=expected_width || height!=expected_height || width<=0 || height<=0 ||
        width%2 || sampling<0 || sampling>=TJ_NUMSAMP) {
        message="JPEG dimensions/sampling do not match stereo metadata";goto cleanup;
    }
    if ((width/2)%tjMCUWidth[sampling]!=0) {
        message="Stereo midpoint is not JPEG MCU-aligned; refusing lossy or shifted crop";goto cleanup;
    }
    tjtransform transforms[2];memset(transforms,0,sizeof(transforms));
    for(int side=0;side<2;++side) {
        transforms[side].r.x=side*(width/2);transforms[side].r.w=width/2;
        transforms[side].r.h=height;transforms[side].op=TJXOP_NONE;
        transforms[side].options=TJXOPT_CROP;
    }
    if(tjTransform(handle,raw,length,2,out,sizes,transforms,TJFLAG_STOPONWARNING)<0) {
        message=tjGetErrorStr2(handle);
        if(tjGetErrorCode(handle)==TJERR_WARNING)status=-2;
        goto cleanup;
    }
cleanup:
    if(message) {strncpy(error,message,511);error[511]=0;}
    if(handle)tjDestroy(handle);
    if(message) {
        for(int side=0;side<2;++side) {ego_free_jpeg(out[side]);out[side]=NULL;sizes[side]=0;}
        return status;
    }
    return 0;
}
