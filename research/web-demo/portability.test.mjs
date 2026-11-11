import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {repositoryRoot,javaHome} from './lab-build.mjs';
const root=path.dirname(fileURLToPath(import.meta.url));
test('独立克隆后从网页目录定位当前仓库，而非开发者磁盘',()=>{
 assert.equal(repositoryRoot,path.resolve(root,'../..'));
 assert.equal(fs.existsSync(path.join(repositoryRoot,'backend/pom.xml')),true);
});
test('Java工具使用JAVA_HOME或系统PATH，不依赖固定安装盘符',()=>{
 assert.equal(javaHome,process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin'):'');
});
test('网页构建只引用仓库内合成夹具，不要求历史target目录存在',()=>{
 const script=fs.readFileSync(new URL('./build.mjs',import.meta.url),'utf8');
 assert.doesNotMatch(script,/[CD]:[\\/](?:Desktop|Users)/);
 assert.doesNotMatch(script,/backend\/target\//);
});
