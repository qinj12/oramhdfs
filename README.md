# oram-hdfs

将ORAM应用于HDFS，实现了文件读写的混淆

### 贡献
1.	根据分布式文件系统HDFS的特点设计框架，将ORAM思想应用到HDFS中，在加密的基础上进一步保护了数据的访问模式，包括1）具体的读写操作 2）文件分块数量 3）文件存储位置 4）文件访问频率，5）文件访问顺序
2.	对Partition ORAM架构进行了改进，每个模块使用Tree ORAM代替Square Root ORAM或者Heirarchical ORAM，提高模型效率
