import ManifoldModule from 'manifold-3d';

let library;
export async function initEngine(options = {}) {
  if (!library) { library = await ManifoldModule(options); library.setup(); }
  return library;
}

// A small arithmetic parser. Model output is data, never executable JavaScript.
export function expression(value, variables = {}) {
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw Error('数値が有限ではありません');
    return value;
  }
  if (typeof value !== 'string' || value.length > 2048) throw Error('数値または寸法式が必要です');
  const tokens = []; const re = /\s*(?:(\d*\.?\d+(?:e[+-]?\d+)?)|(\$?[a-zA-Z_][a-zA-Z_0-9]*)|([+\-*/%(),]))/igy;
  let at = 0;
  while (at < value.trimEnd().length) {
    re.lastIndex = at; const m = re.exec(value);
    if (!m) throw Error(`不正な寸法式: ${value}`);
    tokens.push(m[1] !== undefined ? Number(m[1]) : m[2] || m[3]); at = re.lastIndex;
  }
  let p = 0;
  const fn = {sin:x=>Math.sin(x*Math.PI/180),cos:x=>Math.cos(x*Math.PI/180),tan:x=>Math.tan(x*Math.PI/180),sqrt:Math.sqrt,abs:Math.abs,min:Math.min,max:Math.max,pow:Math.pow,round:Math.round,floor:Math.floor,ceil:Math.ceil};
  function atom() {
    const t = tokens[p++];
    if (t === '-') return -atom(); if (t === '+') return atom();
    if (typeof t === 'number') return t;
    if (t === '(') { const n = sum(); if(tokens[p++] !== ')') throw Error('寸法式の括弧が閉じていません'); return n; }
    if (typeof t === 'string') {
      const id = t.replace(/^\$/, '');
      if(tokens[p] === '(' && Object.hasOwn(fn,id)) {
        p++; const args = [sum()]; while(tokens[p] === ',') {p++;args.push(sum());}
        if(tokens[p++] !== ')') throw Error('関数の括弧が閉じていません');
        return fn[id](...args);
      }
      if(id === 'pi') return Math.PI;
      if(Object.hasOwn(variables,id)) return variables[id];
    }
    throw Error(`未定義の寸法: ${t}`);
  }
  function product() {let n=atom();while(['*','/','%'].includes(tokens[p])){const op=tokens[p++],v=atom();n=op==='*'?n*v:op==='/'?n/v:n%v;}return n;}
  function sum(){let n=product();while(['+','-'].includes(tokens[p])){const op=tokens[p++],v=product();n=op==='+'?n+v:n-v;}return n;}
  const answer=sum();
  if(p!==tokens.length || !Number.isFinite(answer)) throw Error(`計算できない寸法式: ${value}`);
  return answer;
}

export function parseDesign(text) {
  if (typeof text !== 'string') return validateDesign(text);
  let t=text.trim().replace(/^```(?:json)?\s*/i,'').replace(/\s*```$/,'');
  let parsed;
  try { parsed=JSON.parse(t); } catch(e) {
    const begin=t.indexOf('{'),end=t.lastIndexOf('}');
    if(begin<0 || end<=begin) throw Error('AIの結果に3D設計データがありません');
    try{parsed=JSON.parse(t.slice(begin,end+1));}catch{throw Error('AIの設計JSONが途中で切れているか、形式が不正です');}
  }
  return validateDesign(parsed);
}
export function validateDesign(d) {
  if(!d || typeof d!=='object' || !Array.isArray(d.objects) || !d.objects.length) throw Error('objects を含む3D設計が必要です');
  if(d.units && d.units!=='mm') throw Error('設計の単位は mm を指定してください');
  if(!Array.isArray(d.parameters)) d.parameters=[];
  const seen=new Set();
  for(const p of d.parameters){
    if(!/^[A-Za-z_][A-Za-z_0-9]*$/.test(p.id)||seen.has(p.id)||!Number.isFinite(p.value)) throw Error('寸法パラメーターの名前または値が不正です');
    seen.add(p.id);
  }
  d.schema='modelforge-1';d.units='mm';
  return d;
}

export function buildDesign(input, quality=64, merge=false) {
  if(!library) throw Error('3Dエンジンが未起動です');
  const d=validateDesign(input), {Manifold:M, CrossSection:C, Mesh}=library;
  const owned=[];const keep=x=>{owned.push(x);return x;};
  const params=Object.fromEntries(d.parameters.map(p=>[p.id,p.value]));
  const n=(v,vars=params)=>expression(v,vars);
  const v=(x,vars=params,defaultValue=[0,0,0],length=3)=>{
    if(x===undefined)return defaultValue;
    if(!Array.isArray(x)||x.length!==length)throw Error(`${length}個の座標が必要です`);
    return x.map(t=>n(t,vars));
  };
  const positive=x=>{if(!(x>0))throw Error('長さ・半径には正の値が必要です');return x;};
  const seg=Math.max(8,Math.min(512,Math.round(quality)));
  let nodes=0;
  function shape(s,vars=params,depth=0){
    if(!s || typeof s!=='object' || ++nodes>30000 || depth>128)throw Error('形状の構造が複雑すぎます');
    let r;
    const number=(x,f)=>n(x??f,vars),vector=(x,def,len)=>v(x,vars,def,len);
    const children=()=>{if(!Array.isArray(s.children)||!s.children.length)throw Error(`${s.op}: children が必要です`);return s.children.map(c=>shape(c,vars,depth+1));};
    switch(s.op){
      case 'box':r=keep(M.cube(vector(s.size,[10,10,10]).map(positive),true));break;
      case 'sphere':r=keep(M.sphere(positive(number(s.radius,10)),seg));break;
      case 'cylinder':r=keep(M.cylinder(positive(number(s.height,10)),positive(number(s.radius??s.radiusBottom,5)),number(s.radiusTop,s.radius??s.radiusBottom??5),seg,true));break;
      case 'rounded_box':{
        const size=vector(s.size,[20,20,20]).map(positive),rad=positive(number(s.radius,2));
        if(size.some(a=>a<2*rad))throw Error('角丸半径が寸法の半分を超えています');
        const pieces=[];
        const ball=keep(M.sphere(rad,Math.min(seg,40)));
        for(const x of [-1,1])for(const y of [-1,1])for(const z of [-1,1])pieces.push(keep(ball.translate([x*(size[0]/2-rad),y*(size[1]/2-rad),z*(size[2]/2-rad)])));
        r=keep(M.hull(pieces));break;
      }
      case 'torus':{
        const major=positive(number(s.majorRadius,15)),minor=positive(number(s.minorRadius,3));
        if(minor>=major)throw Error('トーラスの太さは中心半径より小さくしてください');
        const cross=keep(C.circle(minor,Math.max(12,Math.round(seg/2))));
        r=keep(M.revolve(keep(cross.translate([major,0])),seg,number(s.angle,360)));break;
      }
      case 'extrude':{
        const rings=[s.points,...(s.holes||[])].map(ring=>{if(!Array.isArray(ring)||ring.length<3)throw Error('断面には3点以上必要です');return ring.map(p=>vector(p,undefined,2));});
        const cs=keep(new C(rings,'EvenOdd'));
        r=keep(M.extrude(cs,positive(number(s.height,10)),Math.max(0,Math.min(256,Math.round(number(s.divisions,0)))),number(s.twist,0),vector(s.scaleTop,[1,1],2),s.center!==false));break;
      }
      case 'revolve':{
        if(!Array.isArray(s.points)||s.points.length<3)throw Error('回転断面には3点以上必要です');
        r=keep(M.revolve(s.points.map(p=>vector(p,undefined,2)),seg,number(s.angle,360)));break;
      }
      case 'union':r=keep(M.union(children()));break;
      case 'subtract':r=keep(M.difference(children()));break;
      case 'intersect':r=keep(M.intersection(children()));break;
      case 'hull':r=keep(M.hull(children()));break;
      case 'repeat':{
        const count=Math.round(number(s.count,1));if(count<1||count>10000)throw Error('繰り返し回数が範囲外です');
        const pieces=[];for(let i=0;i<count;i++)pieces.push(shape(s.shape,{...vars,i},depth+1));
        r=keep(M.union(pieces));break;
      }
      case 'tube':{
        if(!Array.isArray(s.points)||s.points.length<2)throw Error('チューブには2点以上必要です');
        const radius=positive(number(s.radius,2)),pieces=[];
        const spheres=s.points.map((p,i)=>keep(M.sphere(positive(s.radii?number(s.radii[i],radius):radius),Math.min(seg,48)).translate(vector(p))));
        for(let i=1;i<spheres.length;i++)pieces.push(keep(M.hull([spheres[i-1],spheres[i]])));
        r=keep(M.union(pieces));break;
      }
      case 'mesh':{
        if(!Array.isArray(s.vertices)||!Array.isArray(s.triangles))throw Error('メッシュには頂点と三角形が必要です');
        const vertices=s.vertices.flatMap(p=>vector(p)),triangles=s.triangles.flat();
        if(triangles.length%3||triangles.some(i=>!Number.isInteger(i)||i<0||i>=vertices.length/3))throw Error('メッシュの頂点番号が不正です');
        r=keep(new M(new Mesh({numProp:3,vertProperties:new Float32Array(vertices),triVerts:new Uint32Array(triangles)})));break;
      }
      default:throw Error(`未対応の形状: ${s.op}`);
    }
    if(s.scale){const scale=vector(s.scale,[1,1,1]);if(scale.some(a=>a===0))throw Error('倍率 0 は使えません');r=keep(r.scale(scale));}
    if(s.rotate)r=keep(r.rotate(vector(s.rotate)));
    if(s.translate)r=keep(r.translate(vector(s.translate)));
    return r;
  }
  try{
    const bodies=d.objects.map((o,i)=>{
      try{return {name:String(o.name||`パーツ ${i+1}`),id:String(o.id||i),color:/^#[0-9a-f]{6}$/i.test(o.color)?o.color:'#61c5d2',solid:shape(o.shape)};}
      catch(e){throw Error(`${o.name||o.id||i+1}: ${e.message}`);}
    });
    const outputs=merge?[{name:d.title||'model',id:'merged',color:bodies[0].color,solid:keep(M.union(bodies.map(b=>b.solid)))}]:bodies;
    const result=outputs.map(b=>{
      const status=b.solid.status();if(status!=='NoError')throw Error(`${b.name}: 立体を作成できません (${status})`);
      if(b.solid.isEmpty())throw Error(`${b.name}: 計算結果が空です。穴や寸法を確認してください`);
      const m=b.solid.getMesh(),positions=new Float32Array(m.numVert*3);
      for(let i=0;i<m.numVert;i++)for(let j=0;j<3;j++)positions[i*3+j]=m.vertProperties[i*m.numProp+j];
      return {name:b.name,id:b.id,color:b.color,positions,indices:new Uint32Array(m.triVerts),volume:b.solid.volume(),bounds:b.solid.boundingBox()};
    });
    return result;
  }finally{for(let i=owned.length-1;i>=0;i--)owned[i].delete();}
}

export function makeSTL(parts){
  const count=parts.reduce((n,p)=>n+p.indices.length/3,0),out=new ArrayBuffer(84+count*50),dv=new DataView(out);
  new Uint8Array(out,0,80).set(new TextEncoder().encode('ModelForge 3D | millimeters | Z up'));
  dv.setUint32(80,count,true);let offset=84;
  for(const p of parts)for(let i=0;i<p.indices.length;i+=3){
    const vs=[0,1,2].map(k=>p.positions.slice(p.indices[i+k]*3,p.indices[i+k]*3+3));
    const a=vs[1].map((x,j)=>x-vs[0][j]),b=vs[2].map((x,j)=>x-vs[0][j]);
    let normal=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];const len=Math.hypot(...normal)||1;normal=normal.map(x=>x/len);
    for(const x of [...normal,...vs[0],...vs[1],...vs[2]]){dv.setFloat32(offset,x,true);offset+=4;}dv.setUint16(offset,0,true);offset+=2;
  }
  return out;
}
export function makeOBJ(parts){
  const lines=['# ModelForge 3D; units=mm; Z-up'];let base=1;
  for(const p of parts){lines.push('o '+p.name.replace(/[^\p{L}\p{N}_-]/gu,'_'));for(let i=0;i<p.positions.length;i+=3)lines.push(`v ${p.positions[i]} ${p.positions[i+1]} ${p.positions[i+2]}`);for(let i=0;i<p.indices.length;i+=3)lines.push(`f ${base+p.indices[i]} ${base+p.indices[i+1]} ${base+p.indices[i+2]}`);base+=p.positions.length/3;}
  return lines.join('\n')+'\n';
}
