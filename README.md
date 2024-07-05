# hadoop-2.6.0-cdh5.6.0
hadoop-2.6.0-cdh5.6.0

##编译记录
编译所需基础环境如下:   
Apache Maven 3.6.1  
Java version: 1.8.0_201  
Findbugs 1.3.9 (if running findbugs)
ProtocolBuffer 2.5.0
CMake 2.6 or newer (if compiling native code), must be 3.0 or newer on Mac  
Zlib devel (if compiling native code)  
openssl devel ( if compiling native hadoop-pipes )

进入项目根目录，按照BUILDING.txt文档中所述运行如下命令：  
`
`mvn package -Pdist,native,src -DskipTests -Dtar` 

可能遇到的编译错误问题如下:
```
org.apache.maven.enforcer.rule.api.EnforcerRuleException: Detected JDK Version: 
1.8.0-201 is not in the allowed range [1.7.0,1.7.1000}].
```
这里提示错误是jdk版本不对，切换使用的jdk版本至提示信息中相应的jdk7

```
https\://repo.maven.apache.org/maven2/.error=Could not transfer artifact org.apache.maven.doxia\:
doxia-module-markdown\:pom\:1.3 from/to central (https\://repo.maven.apache.org/maven2)\: Received 
fatal alert\: protocol_version
```
编译过程中从远程仓库下载相关依赖时候，报protocol_version网络错误，原因是这里使用了默认使用TLSV1的jdk7  
导致的错误，需要在编译命令中增加`-Dhttps.protocols=TLSv1.2`参数指定使用TLSV2，如下：

`mvn package -Pdist,native,src -Dhttps.protocols=TLSv1.2 -DskipTests -Dtar` 

编译到hadoop-hdfs模块时，出现以下错误：
```
Missing tools.jar at: /Library/Java/JavaVirtualMachines/jdk1.7.0_80.jdk/Contents/Home/Classes/classes.jar.
 Expression: file.exists()
```
这里需要进入到JAVA_HOME目录创建Classes文件夹，并将lib下的tools.jar copy到Classes文件目录下并命名classes.jar即可  

编译到hadoop-dist模块时，出现以下错误：
```
Failed to execute goal org.codehaus.mojo:findbugs-maven-plugin:3.0.0:findbugs (default) on project 
hadoop-project-dist: Unable to parse configuration of mojo org.codehaus.mojo:findbugs-maven-plugin:3.0.0:findbugs 
for parameter pluginArtifacts: Cannot assign configuration entry 'pluginArtifacts' with value '${plugin.artifacts}' 
of type java.util.Collections.UnmodifiableRandomAccessList to property of type java.util.ArrayList
```
这里是maven版本过高的问题，maven版本大于3.5.4就会出现这个问题，切换maven版本至3.5.2，重新编译错误没再发生

编译到hadoop-mapreduce-client-nativetask，出现如下错误：
```
fatal error: 'tr1/tuple' file not found
    #   include <tr1/tuple>  // NOLINT
...
Failed to execute goal org.apache.maven.plugins:maven-antrun-plugin:1.7:run (make) on project 
hadoop-mapreduce-client-nativetask: An Ant BuildException has occured: exec returned: 2
```
这里是找不到 <tr1/tuple> 头文件的问题，根据错误提示找到对应报错的位置，在hadoop-mapreduce-client-nativetask/  
src/main/native/gtest/include/gtest/gtest.h，其中报错位置如下:  
```
#  else
#   include <tr1/tuple>  // NOLINT
#  endif  // !GTEST_HAS_RTTI && GTEST_GCC_VER_ < 40302

...

#endif  // GTEST_HAS_TR1_TUPLE
```
可以看见是在`GTEST_HAS_TR1_TUPLE`中系统找不到<tr1/tuple>头文件，怀疑可能是较高的系统版本Mac OS 10.14.3不兼容导致，  
根据gtest.h中的说明:  
```
GTEST_USE_OWN_TR1_TUPLE  - Define it to 1/0 to indicate whether Google
                           Test's own tr1 tuple implementation should be
                           used.  Unused when the user sets
                           GTEST_HAS_TR1_TUPLE to 0.
```
将`GTEST_HAS_TR1_TUPLE`设置为0后编译通过  

编译到Apache Hadoop Pipes的时候报错，错误主要信息如下:  
```
CMake Error at /Applications/CMake.app/Contents/share/cmake-3.15/Modules/FindPackageHandleStandardArgs.cmake:137 (message):
    Co-- Configuring incomplete, errors occurred!
    See also hadoop-pipes/taruld NOT find OpenSSL, try to set the path to OpenSSL root folder in the
    system variable OPENSSL_ROOT_DIR (missing: OPENSSL_INCLUDE_DIR)
```
Cmake过程中找不到openssl头文件，需要设定`OPENSSL_INCLUDE_DIR`,根据系统中的openssl路径引入该变量重新编译即可:  
`export OPENSSL_INCLUDE_DIR=/usr/local/Cellar/openssl/1.0.2s/incude`

















