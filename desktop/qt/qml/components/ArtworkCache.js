.pragma library

// 远程封面 URL → 后端缓存文件路径 的全局共享缓存。
// .pragma library 的 JS 在一个 engine 内是单例，所有 CoverArt 实例共享同一份。
var resolved = {}
var pending = {}
