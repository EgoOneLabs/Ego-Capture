#ifndef EGO_JPEG_SPLIT_CORE_H
#define EGO_JPEG_SPLIT_CORE_H
/* -2: JPEG data warning (strict transform rejected it); -1: other failure. */
int ego_split_jpeg(const unsigned char *raw, unsigned long length, int width, int height,
                   unsigned char *out[2], unsigned long sizes[2], char error[512]);
void ego_free_jpeg(unsigned char *jpeg);
#endif
