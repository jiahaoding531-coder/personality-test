export class LabInputError extends Error {
  constructor(){super('参数不合法，请检查距离、分钟数、公平权重和整数种子。');this.name='LabInputError';}
}
// 只改本轮合成实验条件，绝不修改长期画像或原始节点。
export function createLabInput(base,options){
 const keys=['maxDistanceKm','remainingMinutes','fairnessWeight','seed'];
 if(!options||typeof options!=='object'||Array.isArray(options)||Object.keys(options).length!==4||keys.some(k=>!Object.hasOwn(options,k)||typeof options[k]!=='number'||!Number.isFinite(options[k])))throw new LabInputError();
 const {maxDistanceKm:d,remainingMinutes:t,fairnessWeight:w,seed:s}=options;
 if(d<.5||d>50||!Number.isInteger(t)||t<1||t>1440||w<0||w>1||!Number.isInteger(s)||s<0||s>2147483647)throw new LabInputError();
 if(base.datasetKind!=='SYNTHETIC_TEST_ONLY'||base.coordinateSystem!=='SYNTHETIC'||base.nodes.length!==6||base.queries.length!==18)throw new LabInputError();
 const result=structuredClone(base);result.seed=s;result.fairnessWeight=w;
 result.queries=result.queries.map(q=>({...q,maxDistanceKm:d,remainingMinutes:t}));
 return result;
}
