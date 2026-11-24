import {initEngine,buildDesign,makeSTL} from './engine.js';
const ready=initEngine({locateFile:path=>new URL(path,import.meta.url).href});
self.onmessage=async ({data})=>{
  try{
    await ready;
    const parts=buildDesign(data.design,data.quality,data.merge);
    if(data.format==='stl'){const buffer=makeSTL(parts);self.postMessage({id:data.id,buffer},[buffer]);}
    else self.postMessage({id:data.id,parts},parts.flatMap(p=>[p.positions.buffer,p.indices.buffer]));
  }catch(e){self.postMessage({id:data.id,error:e.message||String(e)});}
};
