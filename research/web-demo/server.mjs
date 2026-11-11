import http from 'node:http';
import fs from 'node:fs/promises';
import {createLabHandler} from './lab-server.mjs';
import {runLab} from './lab-runtime.mjs';
const html=await fs.readFile(new URL('./index.html',import.meta.url));
const server=http.createServer(createLabHandler({html,run:runLab}));
server.requestTimeout=15000;
server.listen(5186,'127.0.0.1',()=>console.log('TravelMind 本地演示 http://127.0.0.1:5186/'));
