#ifndef _PACKAGE_H
#define _PACKAGE_H


#define QHY_JAVA_PACKAGE_PATH   com_qhyccd_www_mobilenote2

#define JAVA_EXPORT_NAME2(name,package) Java_##package##_##name
#define JAVA_EXPORT_NAME1(name,package) JAVA_EXPORT_NAME2(name,package)
#define JAVA_EXPORT_NAME(name) JAVA_EXPORT_NAME1(name,QHY_JAVA_PACKAGE_PATH)


#endif
