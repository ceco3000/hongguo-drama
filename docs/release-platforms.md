# 三端同步发布规则

从1.1.9开始，每次项目更新统一评估并构建三端：macOS Apple Silicon arm64、macOS Intel x64、Windows x64。使用同一发行版本，分别记录构建、签名、公证、安装和运行验收。

版本逢9进1：1.1.9之后为1.2，1.9.9之后为2.0。工具要求三段SemVer时分别写1.2.0、2.0.0。只按最近公开版本递增，未公开本机测试包不额外跳号。

Mac使用匹配架构的Electron、FFmpeg和ffprobe；Developer ID签名、App与DMG均Apple Accepted并装订票据后发布。Windows独立验证安装/启动/媒体处理，不能用Mac或Rosetta结果代替；有任何平台未完成，发布说明必须写明，不能标记全平台完成。

发行资产统一附SHA256、应用对应源码、各平台准确媒体工具对应源码与许可。公开源码不得包含运行数据、密钥、本机路径、测试profiles或备份。不要把更新自动扩大到官网或Issue评论。
