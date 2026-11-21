(function(root){
const sma=(c,n)=>{if(!c||c.length<n)return null;let s=0;for(let i=c.length-n;i<c.length;i++)s+=c[i];return s/n};
function pivot(H,L,C){const PP=(H+L+C)/3,R=H-L;
 return[{title:'Classic pivot points',rows:[['R3',H+2*(PP-L)],['R2',PP+R],['R1',2*PP-L],['Pivot (PP)',PP],['S1',2*PP-H],['S2',PP-R],['S3',L-2*(H-PP)]]}]}
function fib(H,L,C){const PP=(H+L+C)/3,R=H-L;
 return[{title:'Fibonacci pivot points',rows:[['R3 (1.000)',PP+R],['R2 (0.618)',PP+.618*R],['R1 (0.382)',PP+.382*R],['Pivot (PP)',PP],['S1 (0.382)',PP-.382*R],['S2 (0.618)',PP-.618*R],['S3 (1.000)',PP-R]]},
 {title:'Retracement (from high)',rows:[0,.236,.382,.5,.618,.786,1].map(r=>['Fib '+r,H-r*R])},
 {title:'Extension',rows:[['Up 1.272',L+1.272*R],['Up 1.618',L+1.618*R],['Up 2.000',L+2*R],['Down 1.272',H-1.272*R],['Down 1.618',H-1.618*R]]}]}
const ANG=[['1x8 (7.5°)',1/8],['1x4 (15°)',1/4],['1x3 (18.75°)',1/3],['1x2 (26.25°)',1/2],['1x1 (45°)',1],['2x1 (63.75°)',2],['3x1 (71.25°)',3],['4x1 (75°)',4]];
function gannAngles(H,L,W){const u=(H-L)/Math.max(W,1),n=W+1;
 return[{title:'Fan up from low (next bar)',rows:ANG.map(a=>[a[0],L+a[1]*u*n])},
 {title:'Fan down from high (next bar)',rows:ANG.map(a=>[a[0],H-a[1]*u*n])}]}
function sqrtRows(P,step,names){const s=Math.sqrt(P),rows=[];
 for(let k=3;k>=1;k--)rows.push(['R'+k+(names?' ('+names[k-1]+')':''),Math.pow(s+k*step,2)]);
 for(let k=1;k<=3;k++){const x=s-k*step;if(x>0)rows.push(['S'+k+(names?' ('+names[k-1]+')':''),x*x])}
 return rows}
const STEP={D:.25,W:.5,M:1,A:2};
function sqrtGann(P,tf){const st=STEP[tf]||.25;
 return[{title:'√ Gann around current price (step '+st+' in √, '+st*180+'°)',rows:sqrtRows(P,st)}]}
function sq9b(P){const s=Math.sqrt(P),d=[45,90,180],rows=[];
 for(let k=2;k>=0;k--)rows.push(['R'+(k+1)+' ('+d[k]+'°)',Math.pow(s+d[k]/180,2)]);
 for(let k=0;k<3;k++){const x=s-d[k]/180;if(x>0)rows.push(['S'+(k+1)+' ('+d[k]+'°)',x*x])}
 return[{title:'Gann Square of 9 (45°, 90°, 180°)',rows}]}
function smaRows(c,P){const rows=[];[5,20,50,200].forEach(n=>{const v=sma(c,n);rows.push(['SMA '+n,v===null?NaN:v])});
 return[{title:'Simple moving averages',rows}]}
function compute(tab,f,P,tf){
 switch(tab){case'pivot':return pivot(f.pH,f.pL,f.pC);case'fib':return fib(f.pH,f.pL,f.pC);
 case'gann':return gannAngles(f.wH,f.wL,f.W);case'sqrt':return sqrtGann(P,tf);case'sq9':return sq9b(P);
 case'sma':return smaRows(f.closes,P)}return[]}
// aggregation
const wkKey=d=>Math.floor((d+3)/7);
function aggregate(bars,off,kind){const out=[];let cur=null;
 for(const b of bars){const ms=b.t+off*1000,day=Math.floor(ms/864e5);
  const k=kind==='W'?wkKey(day):(()=>{const d=new Date(ms);return d.getUTCFullYear()*12+d.getUTCMonth()})();
  const ds=new Date(ms).toISOString().slice(0,10);
  if(cur&&cur.k===k){cur.h=Math.max(cur.h,b.h);cur.l=Math.min(cur.l,b.l);cur.c=b.c;cur.e=ds}
  else{cur={k,h:b.h,l:b.l,c:b.c,s:ds,e:ds};out.push(cur)}}
 return out}
function buildFrames(bars,meta){
 const off=meta.gmtoffset||0,now=Date.now(),nowMs=now+off*1000,today=Math.floor(nowMs/864e5);
 const wd=new Date(nowMs).getUTCDay(),weekend=wd===0||wd===6;
 const reg=meta.currentTradingPeriod&&meta.currentTradingPeriod.regular;
 const open=reg&&now/1000>=reg.start&&now/1000<reg.end;
 const D=bars.map(b=>{const ds=new Date(b.t+off*1000).toISOString().slice(0,10);return{h:b.h,l:b.l,c:b.c,s:ds,e:ds}}),W=aggregate(bars,off,'W'),M=aggregate(bars,off,'M');
 const dm=new Date(nowMs),mKey=dm.getUTCFullYear()*12+dm.getUTCMonth();
 const prev=(A,drop)=>{const a=drop&&A.length>1?A.slice(0,-1):A;return a[a.length-1]};
 const closes=bars.map(b=>b.c);
 const win=(A,n)=>{const a=A.slice(-n);return{wH:Math.max(...a.map(x=>x.h)),wL:Math.min(...a.map(x=>x.l)),W:a.length}};
 const lab=x=>x.s===x.e?x.s:x.s+' to '+x.e;
 const mk=(A,drop,n,cl)=>{const p=prev(A,drop),q=A[A.length-1];return Object.assign({pH:p.h,pL:p.l,pC:p.c,pD:lab(p),cH:q.h,cL:q.l,cC:q.c,cD:lab(q),closes:cl},win(A,n))};
 const fr={};
 fr.D=mk(D,open,20,closes);
 fr.W=mk(W,W[W.length-1].k===wkKey(today)&&!weekend,26,W.map(x=>x.c));
 fr.M=mk(M,M[M.length-1].k===mKey,24,M.map(x=>x.c));
 const aH=Math.max(...D.map(x=>x.h)),aL=Math.min(...D.map(x=>x.l));
 fr.A={pH:aH,pL:aL,pC:closes[closes.length-1],cH:aH,cL:aL,cC:closes[closes.length-1],pD:'all data',cD:'all data',wH:aH,wL:aL,W:M.length,closes:closes};
 return fr}
root.Calc={compute,buildFrames,sma};
if(typeof module!=='undefined')module.exports=root.Calc;
})(typeof window!=='undefined'?window:globalThis);
