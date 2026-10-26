/**
 * MIRRAN private vision bridge. Node.js 20+, no npm dependencies.
 * Requires HTTPS reverse proxy, OPENAI_API_KEY, and MIRRAN_ACCESS_TOKEN.
 * NEVER add a secret to Android source or distribute an unauthenticated deployment.
 */
import http from 'node:http';
import {timingSafeEqual} from 'node:crypto';

const apiKey=process.env.OPENAI_API_KEY;
const access=process.env.MIRRAN_ACCESS_TOKEN;
if(!apiKey || !access || access.length<16) {
  throw new Error('Set OPENAI_API_KEY and a random MIRRAN_ACCESS_TOKEN (16+ characters).');
}
const port=Number(process.env.PORT || 8080);
const model=process.env.OPENAI_MODEL || 'gpt-5.4-mini';
const fields=['exposure','highlights','shadows','contrast','vibrance','warmth','clarity'];
const schema={
  type:'object',additionalProperties:false,
  properties:{
    summary:{type:'string'},
    edits:{type:'object',additionalProperties:false,
      properties:Object.fromEntries(fields.map(k=>[k,{type:'number'}])),required:fields}
  }, required:['summary','edits']
};
function authorized(header='') {
  if(!header.startsWith('Bearer '))return false;
  const supplied=Buffer.from(header.slice(7));
  const expected=Buffer.from(access);
  return supplied.length===expected.length && timingSafeEqual(supplied,expected);
}
function json(res,status,obj) {
  res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'});
  res.end(JSON.stringify(obj));
}
const server=http.createServer(async(req,res)=>{
  if(req.method==='GET' && req.url==='/health')return json(res,200,{service:'mirran',status:'ready'});
  if(req.method!=='POST'||req.url!=='/analyze')return json(res,404,{error:'Not found'});
  if(!authorized(req.headers.authorization))return json(res,401,{error:'Unauthorized'});
  try {
    const chunks=[];let total=0;
    for await(const chunk of req) {
      total+=chunk.length;
      if(total>4_000_000)return json(res,413,{error:'Photo preview is too large'});
      chunks.push(chunk);
    }
    const {image,metrics}=JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if(typeof image!=='string'||!image.startsWith('data:image/jpeg;base64,')||
       image.length>3_800_000 || typeof metrics!=='object' || !metrics) {
      return json(res,400,{error:'Invalid image or metrics'});
    }
    const prompt=[
      'You are MIRRAN, a highly skilled natural-photography editing director.',
      'Analyze the visual scene, lighting, subject, atmospheric haze, colour balance, shadow and highlight detail.',
      'Suggest a thoughtful improvement while protecting natural skin, realistic colours, skies, shadow depth and photographic identity.',
      'The image is NOT to be regenerated. Return numeric adjustments for an existing conventional pixel pipeline.',
      'Available parameters normalized -1..1: exposure (-1 to +1 EV), highlights, shadows, contrast, vibrance, warmth, clarity.',
      'Avoid speculative heavy clarity/dehaze. Subtle does not mean timid: do visibly useful edits when warranted.',
      'No subject masks or special effects in this first version. Do not claim tools unavailable in the renderer.',
      'Write summary in one or two sentences describing the actual issues and intended corrections.',
      'All edits must be finite numbers between -1 and 1. Keep unnecessary controls at zero.',
      'Measured image statistics: '+JSON.stringify(metrics)
    ].join(' ');
    const response=await fetch('https://api.openai.com/v1/responses',{
      method:'POST',
      headers:{'Content-Type':'application/json','Authorization':'Bearer '+apiKey},
      body:JSON.stringify({
        model,store:false,max_output_tokens:450,
        text:{format:{type:'json_schema',name:'mirran_edit_plan',strict:true,schema}},
        input:[{role:'user',content:[
          {type:'input_text',text:prompt},
          {type:'input_image',image_url:image,detail:'high'}
        ]}]
      }),
      signal:AbortSignal.timeout(60_000)
    });
    if(!response.ok){
      // Never forward service diagnostics, secrets or sensitive request content.
      return json(res,502,{error:'AI provider returned HTTP '+response.status});
    }
    const data=await response.json();
    const output=(data.output||[]).flatMap(x=>x.content||[])
      .filter(x=>x.type==='output_text').map(x=>x.text).join('');
    const parsed=JSON.parse(output);
    if(typeof parsed.summary!=='string'||!parsed.edits)throw new Error('Invalid AI plan');
    const result={summary:parsed.summary.slice(0,450),edits:{}};
    for(const key of fields) {
      const n=Number(parsed.edits[key]);
      result.edits[key]=Number.isFinite(n)?Math.max(-1,Math.min(1,n)):0;
    }
    return json(res,200,result);
  }catch(error){
    console.error('MIRRAN request failed:',error?.name||'Unknown',error?.message||'');
    return json(res,500,{error:'Unable to complete AI analysis'});
  }
});
server.requestTimeout=70_000;
server.listen(port,()=>console.log('MIRRAN private server listening on '+port));
