import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
const base=(process.argv[2]||'http://127.0.0.1:5173').replace(/\/$/,'');
const api=`${base}/api/yaoming`, players=[];
let controller, consume;
async function request(path,method='GET',body,p,status=200) {
  const res=await fetch(api+path,{method,headers:{Origin:base,'Content-Type':'application/json',...(p?{'X-Resume-Token':p.token}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)});
  const text=await res.text();assert.equal(res.status,status,text);return text?JSON.parse(text):null;
}
async function denied(roomId,playerId,token,status) {
  const res=await fetch(`${api}/rooms/${roomId}/stream?playerId=${playerId}`,{headers:{Origin:base,Accept:'text/event-stream',...(token?{'X-Resume-Token':token}:{})},signal:AbortSignal.timeout(15000)});
  const body=await res.text();assert.equal(res.status,status,body);assert.ok(!body.includes('event:ready'));
}
async function leave(p) {
  const v=await request(`/rooms/${p.roomId}?playerId=${p.playerId}`,'GET',undefined,p);
  await request(`/rooms/${p.roomId}/actions`,'POST',{playerId:p.playerId,version:v.version,requestId:randomUUID(),type:'LEAVE',tileIds:[]},p);
}
try {
  const a=await request('/rooms','POST',{name:'推送专项验收',playerName:'推送甲'},null,201);players.push(a);
  const b=await request(`/rooms/${a.roomId}/join`,'POST',{playerName:'推送乙'});players.push(b);
  await denied(a.roomId,a.playerId,'invalid-token',400);
  await denied(a.roomId,a.playerId,null,400);
  await denied(a.roomId,b.playerId,a.token,400);
  await denied('not-a-room',a.playerId,a.token,404);
  controller=new AbortController();const before=Date.now();
  const response=await fetch(`${api}/rooms/${a.roomId}/stream?playerId=${a.playerId}`,{headers:{Origin:base,Accept:'text/event-stream','X-Resume-Token':a.token},signal:controller.signal});
  assert.equal(response.status,200);assert.match(response.headers.get('content-type'),/text\/event-stream/);
  const reader=response.body.getReader(), decoder=new TextDecoder(), notices=[];let buffer='',failure;
  consume=(async()=>{try{while(true){const {value,done}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true}).replace(/\r/g,'');let end;while((end=buffer.indexOf('\n\n'))>=0){const block=buffer.slice(0,end);buffer=buffer.slice(end+2);const data=block.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trim()).join('\n');if(data)notices.push(JSON.parse(data));}}}catch(e){if(!controller.signal.aborted)failure=e;}finally{reader.releaseLock();}})();
  const waitFor=async(type,ms)=>{const end=Date.now()+ms;while(Date.now()<end){if(failure)throw failure;const n=notices.find(n=>n.type===type);if(n)return n;await new Promise(r=>setTimeout(r,25));}throw Error(`Missing ${type} after ${ms}ms`);};
  await waitFor('READY',5000);const readyMs=Date.now()-before;
  await waitFor('PING',19000);
  await leave(a);players.splice(players.indexOf(a),1);await waitFor('CLOSED',5000);
  await denied(a.roomId,a.playerId,a.token,400);
  for(const notice of notices){assert.deepEqual(Object.keys(notice).sort(),['roomId','type','version']);assert.equal(notice.roomId,a.roomId);}
  console.log(JSON.stringify({ok:true,base,readyMs,received:notices.map(n=>n.type),invalidIdentityDenied:true,leftSeatDenied:true}));
} finally {
  controller?.abort();await consume;
  for(const p of players)try{await leave(p);}catch(e){console.error('Cleanup:',e.message);}
}
