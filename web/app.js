import * as THREE from 'three';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {GLTFExporter} from 'three/addons/exporters/GLTFExporter.js';
import {toCreasedNormals} from 'three/addons/utils/BufferGeometryUtils.js';
import {parseDesign,makeOBJ} from './engine.js';
import {samples} from './samples.js';

const $=id=>document.getElementById(id), clone=x=>JSON.parse(JSON.stringify(x));
let pending=new Map(),rpcId=0,project,parts=[],auth={profiles:[]},models=[],job=null,quality=64,building=false,activeJob=false,lastRequest=null,lastFailure=null,polling=false,appliedJobs=new Set(),saveChain=Promise.resolve(),geometryId=0;
window.__nativeResult=(id,result,error)=>{const p=pending.get(id);if(!p)return;pending.delete(id);if(error)p.reject(Error(error));else p.resolve(result);};
function rpc(action,data={}){
  if(window.Native)return new Promise((resolve,reject)=>{const id=String(++rpcId);pending.set(id,{resolve,reject});Native.call(id,action,JSON.stringify(data));});
  return browserRPC(action,data);
}
async function browserRPC(action,d){
  const list=()=>JSON.parse(localStorage.getItem('mf-projects')||'[]');
  if(action==='getStatus')return {auth:{profiles:[]},job:null};
  if(action==='models')return [];
  if(action==='listProjects')return list().map(p=>({id:p.id,title:p.title,updated:p.updated})).sort((a,b)=>b.updated-a.updated);
  if(action==='loadProject')return list().find(p=>p.id===d.id);
  if(action==='saveProject'){const a=list().filter(p=>p.id!==d.project.id);a.push(d.project);localStorage.setItem('mf-projects',JSON.stringify(a));return {};}
  if(action==='deleteProject'){localStorage.setItem('mf-projects',JSON.stringify(list().filter(p=>p.id!==d.id)));return {};}
  if(action==='exportFile'){const bytes=Uint8Array.from(atob(d.base64),c=>c.charCodeAt(0));download(new Blob([bytes],{type:d.mime}),d.name);return {saved:true};}
  if(action==='openUsage'){window.open('https://chatgpt.com/settings/usage','_blank','noopener');return {};}
  if(action==='startAuth')throw Error('ChatGPTへの接続はAndroidアプリで利用できます。この画面はローカルの動作確認用です。');
  if(action==='ackJob'||action==='cancelJob'||action==='cancelAuth')return {};
  throw Error('この機能はAndroidアプリで利用できます');
}
function toast(text){$('toast').textContent=text;$('toast').classList.add('show');clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').classList.remove('show'),4000);}
function showError(e,repair=false){$('errorMessage').textContent=e.message||String(e);$('errorCard').hidden=false;$('repairBtn').hidden=!repair;tab('create');}
function uid(){return crypto.randomUUID?crypto.randomUUID():Date.now().toString(36)+Math.random().toString(36).slice(2);}
function download(blob,name){const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(a.href),30000);}
function toBase64(buffer){let s='';const b=new Uint8Array(buffer);for(let i=0;i<b.length;i+=32768)s+=String.fromCharCode(...b.subarray(i,i+32768));return btoa(s);}
function tab(name){for(const el of document.querySelectorAll('.tabs button'))el.classList.toggle('active',el.dataset.tab===name);for(const p of document.querySelectorAll('.panel'))p.classList.toggle('active',p.id===name+'Panel');}
function isBusy(){if(activeJob){toast('生成が完了するか、停止してから操作してください');return true;}return false;}

const renderer=new THREE.WebGLRenderer({canvas:$('canvas'),antialias:true,alpha:true,preserveDrawingBuffer:false});
renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.outputColorSpace=THREE.SRGBColorSpace;renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.15;
const scene=new THREE.Scene();const camera=new THREE.PerspectiveCamera(38,1,0.01,100000);camera.up.set(0,0,1);camera.position.set(155,-180,120);
const controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=true;controls.dampingFactor=.08;controls.target.set(0,0,35);
scene.add(new THREE.HemisphereLight(0xd9f3ff,0x46536e,2.9));
for(const [color,power,pos] of [[0xffffff,3.6,[80,-110,200]],[0x87e9ee,2,[-100,-20,90]],[0xffd9ad,2.2,[40,120,150]]]){const l=new THREE.DirectionalLight(color,power);l.position.set(...pos);scene.add(l);}
let grid=new THREE.GridHelper(400,40,0x4b687f,0x2a4057);grid.rotation.x=Math.PI/2;grid.position.z=-.3;grid.material.transparent=true;grid.material.opacity=.48;scene.add(grid);
const root=new THREE.Group();scene.add(root);let bbox=new THREE.Box3(),selectedId='',wire=false;
const resize=new ResizeObserver(()=>{const {width,height}=$('viewport').getBoundingClientRect();if(width<=0||height<=0)return;const nextAspect=width/height,ratio=Math.min(camera.aspect,1)/Math.min(nextAspect,1);camera.position.sub(controls.target).multiplyScalar(ratio).add(controls.target);renderer.setSize(width,height,false);camera.aspect=nextAspect;camera.updateProjectionMatrix();renderer.render(scene,camera);});resize.observe($('viewport'));
renderer.setAnimationLoop(()=>{controls.update();renderer.render(scene,camera);});
document.addEventListener('visibilitychange',()=>{if(document.hidden)renderer.setAnimationLoop(null);else{renderer.setAnimationLoop(()=>{controls.update();renderer.render(scene,camera);});poll();}});
function frame(view='iso'){
  if(bbox.isEmpty())return;const size=bbox.getSize(new THREE.Vector3()),center=bbox.getCenter(new THREE.Vector3()),max=Math.max(...size.toArray(),1);
  const dist=size.length()/2/Math.sin(camera.fov*Math.PI/360)*1.18/Math.min(camera.aspect,1);
  const dir={iso:[1,-1.4,.95],front:[0,-1,.0001],top:[0,-.0001,1],right:[1,0,.0001]}[view];
  camera.position.copy(center).add(new THREE.Vector3(...dir).normalize().multiplyScalar(dist));controls.target.copy(center);camera.near=Math.max(.001,max/10000);camera.far=Math.max(1000,dist*50);camera.updateProjectionMatrix();controls.minDistance=max*.06;controls.maxDistance=dist*15;controls.update();
}
function showParts(newParts,fit=false){
  for(const mesh of [...root.children]){root.remove(mesh);mesh.geometry.dispose();mesh.material.dispose();}
  parts=newParts;
  for(const p of parts){const geo=new THREE.BufferGeometry();geo.setAttribute('position',new THREE.BufferAttribute(p.positions,3));geo.setIndex(new THREE.BufferAttribute(p.indices,1));const displayGeo=toCreasedNormals(geo,Math.PI/3);geo.dispose();const material=new THREE.MeshStandardMaterial({color:p.color,metalness:.16,roughness:.42,wireframe:wire,flatShading:false});const mesh=new THREE.Mesh(displayGeo,material);mesh.userData.partId=p.id;mesh.name=p.name;root.add(mesh);}
  bbox.setFromObject(root);if(!parts.length){$('dimensions').textContent='モデルがありません';$('triangles').textContent='';grid.position.z=-.3;return;}const size=bbox.getSize(new THREE.Vector3());$('dimensions').textContent=size.toArray().map(x=>Number(x.toFixed(1))).join(' × ')+' mm';$('triangles').textContent=parts.reduce((n,p)=>n+p.indices.length/3,0).toLocaleString()+' 面';
  const gsize=Math.max(100,Math.ceil(Math.max(size.x,size.y)/100)*200);grid.scale.set(gsize/400,gsize/400,gsize/400);grid.position.z=bbox.min.z-.2;
  if(fit)frame();highlight(selectedId);
}
let worker=new Worker(new URL('worker.js',import.meta.url),{type:'module'}),workerSequence=0,workerPending=new Map();
worker.onmessage=({data})=>{const p=workerPending.get(data.id);if(!p)return;workerPending.delete(data.id);data.error?p.reject(Error(data.error)):p.resolve(data);};
worker.onerror=e=>{for(const p of workerPending.values())p.reject(Error('3Dエンジンでエラーが発生しました。アプリを開き直してください。'));workerPending.clear();};
function build(design,merge=false,format='mesh'){return new Promise((resolve,reject)=>{const id=++workerSequence;workerPending.set(id,{resolve,reject});worker.postMessage({id,design,quality,merge,format});});}
async function applyDesign(design,{fit=false,label='編集',prompt='',sample=false,history=true,receipt=''}={}){
  const serial=++geometryId;building=true;$('busyOverlay').hidden=false;updateButtons();
  try{
    const parsed=parseDesign(clone(design)),result=await build(parsed);if(serial!==geometryId)return false;
    project.design=parsed;project.title=parsed.title||project.title||'無題のモデル';project.sample=sample;
    if(history)project.history.push({id:uid(),time:Date.now(),label,prompt,design:clone(parsed)});
    if(receipt)project.appliedJob=receipt;
    showParts(result.parts,fit);renderProject();await save();return true;
  }finally{if(serial===geometryId){building=false;$('busyOverlay').hidden=true;updateButtons();}}
}
function updateButtons(){$('generateBtn').disabled=activeJob||building;$('exportBtn').disabled=!parts.length||building;$('undoBtn').disabled=activeJob||building||!project||project.history.length<2;$('imageBtn').disabled=activeJob;$('modelSelect').disabled=activeJob;}
function save(){if(!project)return Promise.resolve();project.draft=$('prompt').value;project.updated=Date.now();const snapshot=clone(project);saveChain=saveChain.catch(()=>{}).then(()=>rpc('saveProject',{project:snapshot}));return saveChain.catch(e=>{$('projectStatus').textContent='保存できませんでした';throw e;});}
function renderProject(){
  $('projectTitle').textContent=project.title;$('projectStatus').textContent=project.sample?'サンプル・端末に保存':`${project.history.length} バージョン・端末に保存`;$('sampleBadge').hidden=!project.sample;
  $('modelDescription').textContent=project.design?.description||'';$('generateBtn').querySelector('span').textContent=project.design&&!project.sample?'指示を反映する':'この内容で作る';
  renderParameters();renderObjects();renderHistory();renderRefs();updateButtons();
}
function renderParameters(){
  const parent=$('parameterList');parent.replaceChildren();
  if(!project.design?.parameters.length){parent.innerHTML='<p class="empty">寸法パラメーターがありません。<br>AIへ「幅を調整できるように」と指示できます。</p>';return;}
  for(const p of project.design.parameters){
    const row=document.createElement('div');row.className='parameter';const head=document.createElement('div');head.className='parameter-header';const label=document.createElement('label');label.textContent=p.name||p.id;const number=document.createElement('input');number.type='number';number.value=p.value;number.step=p.step||.1;number.setAttribute('aria-label',p.name||p.id);number.disabled=activeJob;
    const range=document.createElement('input');range.type='range';range.min=p.min??Math.min(0,p.value*.5);range.max=p.max??Math.max(p.value*2,100);range.step=p.step||.1;range.value=p.value;range.disabled=activeJob;range.setAttribute('aria-label',(p.name||p.id)+' スライダー');
    const change=async value=>{if(isBusy())return;const val=Number(value);if(!Number.isFinite(val))return;const d=clone(project.design);d.parameters.find(a=>a.id===p.id).value=val;try{await applyDesign(d,{label:'寸法を変更',prompt:`${p.name||p.id}: ${p.value} → ${val}`});}catch(e){showError(e);renderParameters();}};
    range.oninput=()=>number.value=range.value;range.onchange=()=>change(range.value);number.onchange=()=>change(number.value);
    const limits=document.createElement('div');limits.className='range-limits';limits.innerHTML=`<span>${Number(range.min)}</span><span>${Number(range.max)}</span>`;head.append(label,number);row.append(head,range,limits);parent.append(row);
  }
}
function highlight(id){selectedId=id;for(const m of root.children)m.material.emissive.set(m.userData.partId===id?0x153b4c:0x000000);for(const row of $('objectList').children)row.classList.toggle('selected',row.dataset.id===id);}
function renderObjects(){const el=$('objectList');el.replaceChildren();for(const [i,o] of (project.design?.objects||[]).entries()){const row=document.createElement('div');row.className='object-row';row.dataset.id=o.id||String(i);const color=document.createElement('input');color.type='color';color.value=o.color||'#61c5d2';color.disabled=activeJob;color.setAttribute('aria-label',`${o.name||o.id}の色`);color.onchange=async()=>{const d=clone(project.design);d.objects[i].color=color.value;try{await applyDesign(d,{label:'色を変更',prompt:o.name||o.id});}catch(e){showError(e);}};const name=document.createElement('button');name.textContent=o.name||o.id||`パーツ ${i+1}`;name.onclick=()=>highlight(row.dataset.id);const volume=document.createElement('small');volume.textContent=((parts[i]?.volume||0)/1000).toFixed(1)+' cm³';row.append(color,name,volume);el.append(row);}highlight(selectedId);}
function renderHistory(){const el=$('historyList');el.replaceChildren();for(const [i,h] of [...project.history].reverse().entries()){const row=document.createElement('div');row.className='history-card';const top=document.createElement('div'),title=document.createElement('strong'),time=document.createElement('time'),text=document.createElement('p');title.textContent=h.label+(i===0?' ・現在':'');time.textContent=new Date(h.time).toLocaleString('ja-JP',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'});top.append(title,time);text.textContent=h.prompt||h.design?.description||'';row.append(top,text);if(i>0){const b=document.createElement('button');b.textContent='この形状を復元';b.onclick=async()=>{if(isBusy())return;try{await applyDesign(h.design,{label:'過去の形状を復元',prompt:h.label,fit:true});toast('形状を復元しました');}catch(e){showError(e);}};row.append(b);}el.append(row);}}
function renderRefs(){const el=$('attachments');el.replaceChildren();for(const r of project.refs||[]){const wrap=document.createElement('div');wrap.className='attachment';const img=document.createElement('img');img.src=r.url;img.alt=r.name||'参考画像';const del=document.createElement('button');del.textContent='×';del.setAttribute('aria-label','参考画像を外す');del.onclick=()=>{if(isBusy())return;project.refs=project.refs.filter(a=>a.id!==r.id);renderRefs();save().catch(showError);};wrap.append(img,del);el.append(wrap);}}
async function newProject(design=null,sample=false){if(isBusy())return;if(project)await save();project={id:uid(),title:'新しいプロジェクト',design:null,history:[],refs:[],draft:'',updated:Date.now(),sample:false};$('prompt').value='';if(design)await applyDesign(design,{fit:true,label:sample?'サンプルを作成':'読み込み',sample});else {showParts([],false);renderProject();await save();}}
async function openProject(p){if(isBusy())return;if(project)await save();project=p;project.history ||= [];project.refs ||= [];project.draft ||= '';$('prompt').value=project.draft;quality=project.quality||64;$('qualitySelect').value=quality;if(project.design)await applyDesign(project.design,{fit:true,sample:project.sample,history:false});else{showParts([],false);renderProject();}}
async function listProjects(){const list=await rpc('listProjects');const el=$('projectList');el.replaceChildren();for(const p of list){const row=document.createElement('div');row.className='project-row';const b=document.createElement('button');const name=document.createElement('span');name.textContent=p.title;const time=document.createElement('small');time.textContent=new Date(p.updated).toLocaleString('ja-JP');b.append(name,time);b.onclick=async()=>{try{if(isBusy())return;await openProject(await rpc('loadProject',{id:p.id}));$('projectsDialog').close();}catch(e){showError(e);}};const del=document.createElement('button');del.textContent='削除';del.onclick=async()=>{if(isBusy())return;if(!confirm(`「${p.title}」を削除しますか？`))return;await rpc('deleteProject',{id:p.id});if(project.id===p.id){project=null;await newProject();}await listProjects();};row.append(b,del);el.append(row);}}
function renderAuth(){
  const current=auth.profiles?.find(p=>p.id===auth.active);$('accountLabel').textContent=current?.connected?(current.email||current.label||'接続済み'):'接続する';$('accountBtn').classList.toggle('connected',!!current?.sharing);$('planBadge').textContent=current?.sharing?'Using ChatGPT plan':'未接続';
  const el=$('accountList');el.replaceChildren();for(const p of auth.profiles||[]){const row=document.createElement('div');row.className='account-row'+(auth.active===p.id?' active':'');const info=document.createElement('div');info.textContent=p.email||p.label||'未接続アカウント';const sub=document.createElement('small');sub.textContent=`${p.label} · ${p.sharing?'ChatGPTプラン利用可':p.connected?'利用許可が必要':'再ログインが必要'}`;info.append(sub);const b=document.createElement('button');b.textContent=p.id===auth.active?'選択中':'選択';b.onclick=async()=>{if(isBusy())return;try{auth=await rpc('switchAccount',{id:p.id});renderAuth();await loadModels();}catch(e){toast(e.message);}};row.append(info,b);el.append(row);}
  const signing=auth.signingIn;$('authMessage').hidden=!auth.message;$('authMessage').textContent=auth.message||'';$('cancelLoginBtn').hidden=!signing;$('signInBtn').disabled=signing||activeJob;$('newAccountBtn').disabled=signing||activeJob;$('signOutBtn').disabled=!current?.connected||activeJob;$('refreshModelsBtn').disabled=!current?.sharing||activeJob;
}
async function loadModels(){try{models=await rpc('models');const old=$('modelSelect').value||project?.model;$('modelSelect').replaceChildren();for(const m of models){const o=document.createElement('option');o.value=m.slug;o.textContent=m.display_name||m.slug;$('modelSelect').append(o);}if(models.some(m=>m.slug===old))$('modelSelect').value=old;if(!models.length){const o=document.createElement('option');o.value='';o.textContent='ChatGPTに接続してください';$('modelSelect').append(o);}}catch(e){toast(e.message);}}
async function generate(repair=false){
  if(activeJob||building)return;const text=$('prompt').value.trim();if(!repair&&!text){toast('作成・修正の内容を入力してください');$('prompt').focus();return;}
  const current=auth.profiles?.find(p=>p.id===auth.active);if(!current?.sharing){$('accountDialog').showModal();return;}
  const model=$('modelSelect').value;if(!model){toast('利用するモデルを選択してください');return;}
  await save();$('errorCard').hidden=true;project.model=model;
  const request={projectId:project.id,model,prompt:repair?`直前の生成結果が立体化できませんでした。エラー: ${lastFailure.error}。元の依頼: ${lastRequest?.prompt||text}。次のJSONをスキーマに沿って修正し、完全な設計を返してください。\n${lastFailure.raw}`:text,design:project.design,currentIsSample:project.sample,history:project.history.filter(h=>h.label==='AI生成').slice(-6).map(h=>h.prompt),refs:(project.refs||[]).map(r=>r.id)};
  lastRequest=request;try{job=await rpc('startJob',request);activeJob=true;renderJob();renderParameters();renderObjects();}catch(e){showError(e);}
}
function renderJob(){const running=job&&['queued','running'].includes(job.status);activeJob=!!running;$('jobCard').hidden=!running;$('jobTitle').textContent='3D設計を生成中';$('jobDetail').textContent=running?`${Number(job.chars||0).toLocaleString()} 文字を受信${job.chars?'':'・モデルの応答待ち'}。画面を閉じても処理を続けます。`:'';updateButtons();}
async function applyJob(){
  if(!job||job.status!=='completed'||appliedJobs.has(job.id))return;
  appliedJobs.add(job.id);activeJob=false;
  if(project.appliedJob===job.id){await rpc('ackJob',{id:job.id});return;}
  if(job.projectId!==project.id){const original=await rpc('loadProject',{id:job.projectId});if(!original)throw Error('生成先のプロジェクトが見つかりません');await openProject(original);}
  try{await applyDesign(parseDesign(job.output),{fit:true,label:'AI生成',prompt:job.prompt,receipt:job.id});await rpc('ackJob',{id:job.id});toast('3Dモデルを作成しました');}
  catch(e){lastFailure={error:e.message,raw:job.output};showError(e,true);await rpc('ackJob',{id:job.id});}
}
async function poll(){if(polling)return;polling=true;try{const result=await rpc('getStatus');const was=auth.profiles?.find(p=>p.id===auth.active)?.sharing;const old=auth.active;auth=result.auth||{profiles:[]};renderAuth();const now=auth.profiles?.find(p=>p.id===auth.active)?.sharing;if((!was&&now)||old!==auth.active){await loadModels();if(now&&$('accountDialog').open){$('accountDialog').close();toast('ChatGPTに接続しました');}}job=result.job;renderJob();if(job?.status==='completed')await applyJob();else if(job&&['failed','interrupted','cancelled'].includes(job.status)&&!appliedJobs.has(job.id)){appliedJobs.add(job.id);if(job.status!=='cancelled'){lastRequest=job.request||lastRequest;showError(job.error||'生成が中断されました。最後に完成した形状は保存されています。');}await rpc('ackJob',{id:job.id});renderParameters();renderObjects();}}catch(e){console.warn('Status:',e.message);}finally{polling=false;}}
async function exportFile(format){if(building||!project.design)return;try{$('exportDialog').close();toast('ファイルを作成しています');let data,mime,name;const safe=(project.title||'model').replace(/[\\/:*?"<>|\x00-\x1f]/g,'_');
  if(format==='stl'){const r=await build(project.design,true,'stl');data=r.buffer;name=safe+'.stl';mime='model/stl';}
  else if(format==='obj'){data=new TextEncoder().encode(makeOBJ(parts)).buffer;name=safe+'.obj';mime='text/plain';}
  else if(format==='glb'){
    const exportRoot=new THREE.Group();exportRoot.rotation.x=-Math.PI/2;exportRoot.scale.setScalar(.001);
    for(const m of root.children){const c=m.clone();c.material=m.material.clone();c.material.emissive.set(0);c.material.wireframe=false;exportRoot.add(c);}
    data=await new GLTFExporter().parseAsync(exportRoot,{binary:true});for(const c of exportRoot.children)c.material.dispose();name=safe+'.glb';mime='model/gltf-binary';
  }else{const p=clone(project);p.refs=[];data=new TextEncoder().encode(JSON.stringify({format:'modelforge-project-1',project:p},null,2)).buffer;name=safe+'.modelforge.json';mime='application/json';}
  const result=await rpc('exportFile',{name,mime,base64:toBase64(data)});if(result.saved)toast('ファイルを保存しました');
}catch(e){showError(e);}}
async function importData(text){const parsed=JSON.parse(text);if(parsed.format==='modelforge-project-1'&&parsed.project){const p=parsed.project;parseDesign(p.design);p.id=uid();p.refs=[];p.appliedJob=null;await openProject(p);await save();}else await newProject(parseDesign(parsed));$('projectsDialog').close();toast('設計データを読み込みました');}

for(const b of document.querySelectorAll('[data-tab]'))b.onclick=()=>tab(b.dataset.tab);
for(const b of document.querySelectorAll('[data-close]'))b.onclick=()=>b.closest('dialog').close();
for(const b of document.querySelectorAll('[data-view]'))b.onclick=()=>frame(b.dataset.view);
for(const b of document.querySelectorAll('[data-export]'))b.onclick=()=>exportFile(b.dataset.export);
$('fitBtn').onclick=()=>frame();$('wireBtn').onclick=()=>{wire=!wire;for(const m of root.children)m.material.wireframe=wire;$('wireBtn').classList.toggle('active',wire);};$('gridBtn').onclick=()=>{grid.visible=!grid.visible;$('gridBtn').classList.toggle('active',grid.visible);};
$('accountBtn').onclick=()=>{renderAuth();$('accountDialog').showModal();};
$('signInBtn').onclick=()=>rpc('startAuth',{newAccount:false}).then(poll).catch(e=>toast(e.message));$('newAccountBtn').onclick=()=>rpc('startAuth',{newAccount:true}).then(poll).catch(e=>toast(e.message));$('cancelLoginBtn').onclick=()=>rpc('cancelAuth').then(poll);
$('refreshModelsBtn').onclick=()=>loadModels();$('signOutBtn').onclick=()=>rpc('logout').then(async()=>{await poll();await loadModels();}).catch(e=>toast(e.message));$('usageBtn').onclick=()=>rpc('openUsage').catch(e=>toast(e.message));
$('generateBtn').onclick=()=>generate().catch(showError);$('retryBtn').onclick=async()=>{if(lastRequest){$('prompt').value=lastRequest.prompt;}await generate();};$('repairBtn').onclick=()=>generate(true).catch(showError);$('cancelBtn').onclick=()=>rpc('cancelJob').then(poll);$('errorCloseBtn').onclick=()=>$('errorCard').hidden=true;
$('prompt').addEventListener('input',()=>{clearTimeout(save.timer);save.timer=setTimeout(()=>save().catch(e=>toast(e.message)),500);});
$('modelSelect').onchange=()=>{project.model=$('modelSelect').value;save().catch(showError);};
$('imageBtn').onclick=async()=>{if(window.Native){try{const r=await rpc('pickImages');project.refs.push(...(r.refs||[]));renderRefs();await save();}catch(e){toast(e.message);}}else $('browserImages').click();};
$('browserImages').onchange=async e=>{for(const f of e.target.files){const data=await new Promise(r=>{const reader=new FileReader();reader.onload=()=>r(reader.result);reader.readAsDataURL(f);});project.refs.push({id:uid(),url:data,name:f.name});}renderRefs();save().catch(showError);e.target.value='';};
$('projectsBtn').onclick=async()=>{await listProjects();$('projectsDialog').showModal();};$('newProjectBtn').onclick=async()=>{await newProject();$('projectsDialog').close();tab('create');};
$('renameBtn').onclick=async()=>{if(isBusy())return;const name=prompt('プロジェクト名',project.title);if(name?.trim()){project.title=name.trim();if(project.design)project.design.title=project.title;renderProject();await save();}};
$('exportBtn').onclick=()=>$('exportDialog').showModal();
$('qualitySelect').onchange=async()=>{quality=Number($('qualitySelect').value);project.quality=quality;try{if(project.design)await applyDesign(project.design,{history:false,sample:project.sample});else await save();}catch(e){showError(e);}};
$('undoBtn').onclick=async()=>{if(isBusy()||project.history.length<2)return;const prev=project.history[project.history.length-2];try{await applyDesign(prev.design,{label:'元に戻す',prompt:prev.label,fit:true});}catch(e){showError(e);}};
$('jsonBtn').onclick=()=>{if(!project.design)return;$('jsonEditor').value=JSON.stringify(project.design,null,2);$('jsonDialog').showModal();};$('applyJsonBtn').onclick=async()=>{if(isBusy())return;try{await applyDesign(parseDesign($('jsonEditor').value),{fit:true,label:'JSONを編集'});$('jsonDialog').close();}catch(e){toast(e.message);}};
$('importBtn').onclick=async()=>{if(isBusy())return;if(window.Native){try{const result=await rpc('importProject');if(result.text)await importData(result.text);}catch(e){toast(e.message);}}else $('browserImport').click();};$('browserImport').onchange=async e=>{try{if(e.target.files[0])await importData(await e.target.files[0].text());}catch(e){toast(e.message);}e.target.value='';};
for(const [i,s] of samples.entries()){const b=document.createElement('button');b.className='sample-card';const icon=document.createElement('span');icon.className='sample-icon';icon.textContent=['▱','♧','♙','⌑'][i];const text=document.createElement('div'),title=document.createElement('strong'),desc=document.createElement('small');title.textContent=s.label;desc.textContent=s.description;text.append(title,desc);b.append(icon,text);b.onclick=()=>newProject(clone(s.design),true).catch(showError);$('samples').append(b);}
let pointerStart;renderer.domElement.addEventListener('pointerdown',e=>{pointerStart=[e.clientX,e.clientY];});renderer.domElement.addEventListener('pointerup',e=>{if(!pointerStart||Math.hypot(e.clientX-pointerStart[0],e.clientY-pointerStart[1])>5)return;const r=renderer.domElement.getBoundingClientRect(),ray=new THREE.Raycaster();ray.setFromCamera(new THREE.Vector2((e.clientX-r.left)/r.width*2-1,-(e.clientY-r.top)/r.height*2+1),camera);const hit=ray.intersectObjects(root.children)[0];highlight(hit?.object.userData.partId||'');});
window.__app={get project(){return project;},get parts(){return parts;},applyDesign,newProject,build,exportFile,importData,parseDesign};
async function start(){try{const list=await rpc('listProjects');if(list.length)await openProject(await rpc('loadProject',{id:list[0].id}));else await newProject(clone(samples[0].design),true);await poll();await loadModels();}catch(e){showError(e);}setInterval(poll,1300);}
start();
