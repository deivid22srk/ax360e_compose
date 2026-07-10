/// SPDX-License-Identifier: WTFPL

#ifndef AX360E_DOCUMENT_FILE_H
#define AX360E_DOCUMENT_FILE_H

#include <string>
#include <vector>
#include <jni.h>

class DocumentFile {
private:
    JavaVM *jvm_;
    jobject documentFileObj_;

    static JNIEnv* get_env(JavaVM *vm);

public:
    DocumentFile(JavaVM *vm, jobject documentFileObj);
    ~DocumentFile();

    //static DocumentFile *fromTreeUri(JNIEnv *env, jobject context, jobject uri);
    //static DocumentFile *fromSingleUri(JNIEnv *env, jobject context, jobject uri);

    static std::unique_ptr<DocumentFile> find(JavaVM *vm, jobject uri);
    static std::unique_ptr<DocumentFile> clone(std::unique_ptr<DocumentFile>& file);

    bool exists() const;

    std::unique_ptr<DocumentFile> getParentFile() const;
    std::string getName() const;

    bool isDirectory() const;

    bool isFile() const;

    jobject getUri() const;

    std::vector<std::unique_ptr<DocumentFile>> listFiles() const;

    int64_t length();

    int64_t lastModified();

    static int open_fd(const std::unique_ptr<DocumentFile>& file);
};

#endif //AX360E_DOCUMENT_FILE_H
