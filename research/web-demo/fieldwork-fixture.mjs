// 仅供自动化测试，所有观察均为合成记录，不随网页构建发布。
export function fixture() {
  return {
    schemaVersion:1,artifactType:'FIELDWORK_IMPORT_DRAFT',dataVerifiedByTool:false,experimentAuthorized:false,
    materials:[{id:'F_TEST',scope:'合成测试',title:'合成材料并非现场照片',location:'TEST_ONLY.jpg',description:null,publicationDate:null,collectedOn:'2026-11-02',reuseScope:'仅测试',reviewStatement:'未复核',workbookRow:'来源登记!18',verified:false}],
    points:[{nodeId:'YEX-01',pointId:'TEST_POINT',role:'CAMERA_LOCATION',description:'合成拍摄点',longitude:0,latitude:0,coordinateSystem:'UNKNOWN',method:null,accuracyMeters:null,collectedAt:null,materialId:'F_TEST',recordReference:null,workbookRow:'入口坐标!8',verified:false,adopted:false}],
    facts:[{nodeId:'YEX-01',scope:'NODE',topic:'合成金额测试',observation:'这是合成测试不是现场票价',numericValue:0,applicability:null,materialId:'F_TEST',pageReference:null,observedAt:null,investigatorCode:null,uncertainty:null,workbookRow:'现场事实!10',verified:false}],
    tasks:[],reviews:[],issues:[{severity:'GAP',code:'POINT_SYSTEM_UNKNOWN',location:'入口坐标!8',message:'坐标基准待确认'}],
    counts:{materials:1,points:1,facts:1,tasks:0,reviews:0},inputSha256:{workbook:'a'.repeat(64),register:'b'.repeat(64)}
  };
}
export const nodeIds=['YEX-01','YEX-02','YEX-03'];
