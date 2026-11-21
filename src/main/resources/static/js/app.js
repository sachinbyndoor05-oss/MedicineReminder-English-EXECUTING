let medicines=[],family=[],history=[],me=null,audioCtx,alarmAudio=null,activeReminder=null,activeNativeNotification=null,pendingCustomRingtone='';
let notificationLanguage=localStorage.getItem('medimate-notification-language')||'en';
const ringtoneDBPromise=new Promise((resolve,reject)=>{try{const r=indexedDB.open('MediMateRingtones',1);r.onupgradeneeded=()=>r.result.createObjectStore('ringtones');r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)}catch(e){reject(e)}});
function ringtoneStore(id,data){return ringtoneDBPromise.then(db=>new Promise((res,rej)=>{const t=db.transaction('ringtones','readwrite');t.objectStore('ringtones').put(data,String(id));t.oncomplete=()=>res();t.onerror=()=>rej(t.error)}))}
function ringtoneLoad(id){return ringtoneDBPromise.then(db=>new Promise((res,rej)=>{const t=db.transaction('ringtones','readonly');const r=t.objectStore('ringtones').get(String(id));r.onsuccess=()=>res(r.result||'');r.onerror=()=>rej(r.error)})).catch(()=>localStorage.getItem(customKey(id))||'')}
function ringtoneRemove(id){return ringtoneDBPromise.then(db=>new Promise((res,rej)=>{const t=db.transaction('ringtones','readwrite');t.objectStore('ringtones').delete(String(id));t.oncomplete=()=>res();t.onerror=()=>rej(t.error)})).catch(()=>{})}
const $=s=>document.querySelector(s);
document.querySelectorAll('.nav').forEach(b=>b.onclick=()=>showPage(b.dataset.page));
document.addEventListener('click',()=>prepareAudio(),{passive:true});
$('#todayLabel').textContent=new Date().toLocaleDateString(undefined,{weekday:'long',month:'short',day:'numeric'});
$('#loginForm').onsubmit=login;$('#registerForm').onsubmit=register;$('#profileForm').onsubmit=saveProfile;$('#photoInput').onchange=photoChanged;
if($('#notificationLanguage')){$('#notificationLanguage').value=notificationLanguage;$('#notificationLanguage').onchange=e=>setNotificationLanguage(e.target.value);}
function showLogin(){$('#loginBox').classList.remove('hidden');$('#registerBox').classList.add('hidden')}function showRegister(){$('#loginBox').classList.add('hidden');$('#registerBox').classList.remove('hidden')}
async function api(url,opt={}){let r=await fetch('/api'+url,opt);let d={};try{d=await r.json()}catch{}if(r.status===401){logout(false);throw Error(d.error||'Please login')}if(!r.ok)throw Error(d.error||'Request failed');return d}
async function login(e){e.preventDefault();prepareAudio();try{me=await api('/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:$('#loginEmail').value,password:$('#loginPass').value})});startApp()}catch(x){toast(x.message)}}
async function register(e){e.preventDefault();prepareAudio();try{me=await api('/auth/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:$('#regName').value,email:$('#regEmail').value,phone:$('#regPhone').value,password:$('#regPass').value})});startApp()}catch(x){toast(x.message)}}
async function startApp(){localStorage.setItem('loggedIn','1');prepareAudio();$('#authScreen').classList.add('hidden');$('#app').classList.remove('hidden');renderUser();await load();check();toast('Welcome to MediMate ✦')}
async function boot(){try{me=await api('/auth/me');startApp()}catch{showLogin()}}boot();
async function load(){try{[family,medicines,history]=await Promise.all([api('/family'),api('/medicines'),api('/history')]);renderAll()}catch(x){toast(x.message)}}
function renderUser(){if(!me)return;$('#miniName').textContent=me.name.split(' ')[0];setAvatar($('#miniAvatar'),me);$('#profileName').value=me.name||'';$('#profileEmail').value=me.email||'';$('#profilePhone').value=me.phone||'';setPhoto($('#profilePhoto'),me)}
function setAvatar(el,u){if(u.photo){el.innerHTML='<img src="'+u.photo+'">'}else el.textContent=(u.name||'U')[0].toUpperCase()}
function setPhoto(el,u){if(u.photo)el.innerHTML='<img src="'+u.photo+'">';else el.textContent=(u.name||'U')[0].toUpperCase()}
function renderAll(){renderStats();renderToday();renderMedicines();renderFamily();renderHistory()}
function renderStats(){$('#stats').innerHTML=`<div class="stat"><small>Active medicines</small><strong>${medicines.length}</strong></div><div class="stat"><small>Family profiles</small><strong>${family.length}</strong></div><div class="stat"><small>Completed doses</small><strong>${history.length}</strong></div>`}
function renderToday(){let b=$('#todayList');if(!medicines.length){b.innerHTML='<div class="dose empty"><div><b>No medicines yet</b>Add your first medicine to create today’s schedule.</div></div>';return}b.innerHTML=medicines.map(m=>`<div class="dose"><div class="dose-time">${fmt(m.time)}</div><div class="pill-icon">💊</div><div class="dose-info"><b>${esc(m.name_en||m.name)}</b><span>${esc(m.dosage||'Dose')} • ${esc(m.family_name||'Me')}</span></div>${takenToday(m.id)?'<span class="status taken">Taken ✓</span>':'<button class="status" onclick="takeDose('+m.id+')">Mark taken</button>'}</div>`).join('')}
function renderMedicines(){let b=$('#medicineGrid');if(!medicines.length){b.innerHTML='<div class="medicine-card empty" style="grid-column:1/-1"><b>💊 No medicine schedules</b><span>Add your first daily medicine to get started.</span></div>';return}b.innerHTML=medicines.map(m=>`<article class="medicine-card"><div class="card-top"><div class="med-icon">💊</div><div class="med-names"><b>${esc(m.name_en||m.name)}</b><small>${esc(m.dosage||'Dose')} • ${esc(m.family_name||'Me')}</small></div><span class="status">Active</span></div><div class="med-time">${fmt(m.time)}</div><div class="med-meta">📅 Daily • 🔔 ${esc(m.ringtone||'soft-chime')} ${m.notes?'• '+esc(m.notes):''}</div><div class="card-actions"><button class="mini" onclick="editMed(${m.id})">Edit</button><button class="mini danger" onclick="delMed(${m.id})">Delete</button></div></article>`).join('')}
function renderFamily(){let b=$('#familyGrid');b.innerHTML=family.map(f=>`<article class="family-card"><div class="family-avatar">${f.photo?'<img src="'+esc(f.photo)+'">':'👤'}</div><div class="family-copy"><h3>${esc(f.name)}</h3><p>${esc(f.relation||'Family member')}</p></div><div class="card-actions"><button class="mini danger" onclick="delFamily(${f.id})">Remove</button></div></article>`).join('')}
function renderHistory(){let b=$('#historyList');if(!history.length){b.innerHTML='<div class="empty"><b>No completed doses yet</b>Mark a dose as taken to see it here.</div>';return}b.innerHTML=history.map(x=>`<div class="history-row"><div><b>${esc(x.name)}</b><div class="muted">${new Date(x.taken_at).toLocaleString()}</div></div><span class="status taken">Taken ✓</span></div>`).join('')}
function showPage(p){document.querySelectorAll('.page').forEach(x=>x.classList.remove('active-page'));$('#'+p).classList.add('active-page');document.querySelectorAll('.nav').forEach(x=>x.classList.toggle('active',x.dataset.page===p))}
async function openMedicineModal(existing){
  let m=existing||{};
  pendingCustomRingtone=(m.ringtone==='custom' && m.id ? await ringtoneLoad(m.id) : '')||'';
  const selectedRing=m.ringtone||'soft-chime';
  $('#modalContent').innerHTML=`<h2>💊 ${existing?'Edit':'Add'} medicine</h2>
  <p class="muted">Enter the medicine name in English. Notifications can still be shown in English, Hindi or Kannada.</p>
  <form id="medForm"><div class="form-grid">
  <div class="field full"><label>Medicine name *</label><input id="mNameEn" required value="${esc(m.name_en||m.name||'')}" placeholder="e.g. Paracetamol"></div>
  <div class="field"><label>Dosage</label><input id="mDose" value="${esc(m.dosage||'')}" placeholder="1 tablet"></div>
  <div class="field"><label>Time *</label><input id="mTime" type="time" required value="${m.time||'08:00'}"></div>
  <div class="field"><label>Frequency</label><select id="mFreq"><option value="Daily">Daily</option></select></div>
  <div class="field"><label>For</label><select id="mFamily">${family.map(f=>`<option value="${f.id}" ${String(f.id)===String(m.family_id)?'selected':''}>👤 ${esc(f.name)} — ${esc(f.relation)}</option>`).join('')}</select></div>
  <div class="field"><label>Ringtone</label><select id="mRing"><option value="soft-chime">Soft Chime</option><option value="digital">Digital Alert</option><option value="gentle">Gentle Bells</option><option value="custom">My uploaded ringtone</option></select></div>
  <div class="field full custom-ring-field"><label>Upload your own ringtone <span class="muted">(MP3, WAV, OGG • max 5 MB)</span></label>
    <input id="customRingInput" type="file" accept="audio/*">
    <div id="customRingStatus" class="file-status">${pendingCustomRingtone?'✓ Custom ringtone already saved for this medicine.':'No custom ringtone selected.'}</div>
  </div>
  <div class="field full"><label>Notes</label><textarea id="mNotes" rows="2">${esc(m.notes||'')}</textarea></div>
  </div><div class="form-footer"><button type="button" class="cancel" onclick="closeModal()">Cancel</button><button class="primary">💾 ${existing?'Save changes':'Save Medicine'}</button></div></form>`;
  $('#mFreq').value='Daily';
  $('#mRing').value=selectedRing;
  $('#customRingInput').onchange=customRingtoneChanged;
  $('#mRing').onchange=()=>{ if($('#mRing').value==='custom' && !pendingCustomRingtone) $('#customRingInput').focus(); };
  $('#medForm').onsubmit=e=>saveMed(e,existing?.id);
  openModal();
}

async function customRingtoneChanged(e){
  const f=e.target.files[0];
  if(!f)return;
  if(f.size>5*1024*1024){toast('Ringtone must be under 5 MB');e.target.value='';return;}
  if(!f.type.startsWith('audio/')){toast('Please choose an audio file');e.target.value='';return;}
  const reader=new FileReader();
  reader.onload=()=>{pendingCustomRingtone=reader.result;$('#mRing').value='custom';$('#customRingStatus').textContent='✓ '+f.name+' selected';toast('Custom ringtone added ✓');};
  reader.readAsDataURL(f);
}

async function saveMed(e,id){
  e.preventDefault();
  try{
    const ringtone=$('#mRing').value;
    if(ringtone==='custom' && !pendingCustomRingtone){
      toast('Please upload your custom ringtone first');
      $('#customRingInput').focus();
      return;
    }
    const result=await api('/medicines'+(id?'/'+id:''),{
      method:id?'PUT':'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({
        name:$('#mNameEn').value,name_en:$('#mNameEn').value,name_hi:$('#mNameEn').value,name_kn:$('#mNameEn').value,
        dosage:$('#mDose').value,time:$('#mTime').value,frequency:'Daily',familyId:$('#mFamily').value,
        ringtone,notes:$('#mNotes').value
      })
    });
    const medicineId=id||result.id;
    if(ringtone==='custom' && pendingCustomRingtone){
      await ringtoneStore(medicineId,pendingCustomRingtone).catch(()=>{throw Error('Could not save ringtone. Please choose a smaller file.')});
    }else if(ringtone!=='custom'){
      ringtoneRemove(medicineId);
    }
    closeModal();await load();toast(id?'Medicine updated':'Medicine added');
  }catch(x){toast(x.message)}
}

function editMed(id){openMedicineModal(medicines.find(x=>x.id==id))}
async function delMed(id){
  if(!confirm('Delete this medicine?'))return;
  if(activeReminder?.id==id) stopReminder(false);
  await api('/medicines/'+id,{method:'DELETE'});
  ringtoneRemove(id);
  await load();toast('Medicine deleted');
}
function openFamilyModal(){$('#modalContent').innerHTML=`<h2>Add family member</h2><p class="muted">Create a separate schedule profile.</p><form id="familyForm"><div class="field"><label>Name *</label><input id="fName" required placeholder="Mom"></div><div class="field"><label>Relation</label><input id="fRel" value="Family" placeholder="Mother"></div><div class="field full"><label>Profile photo <span class="muted">(JPG, PNG or WebP • max 2 MB)</span></label><input id="fPhoto" type="file" accept="image/*"><div id="fPhotoStatus" class="file-status">No photo selected.</div></div><div class="form-footer"><button type="button" class="cancel" onclick="closeModal()">Cancel</button><button class="primary">Add member</button></div></form>`;$('#familyForm').onsubmit=saveFamily;$('#fPhoto').onchange=familyPhotoChanged;openModal()}
function familyPhotoChanged(e){const f=e.target.files[0];if(!f)return;if(f.size>2*1024*1024){toast('Family photo must be under 2 MB');e.target.value='';return}if(!f.type.startsWith('image/')){toast('Please choose an image file');e.target.value='';return}const r=new FileReader();r.onload=()=>{$('#fPhotoStatus').textContent='✓ '+f.name+' selected';$('#fPhotoStatus').dataset.photo=r.result};r.readAsDataURL(f)}
async function saveFamily(e){e.preventDefault();try{await api('/family',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:$('#fName').value,relation:$('#fRel').value,photo:$('#fPhotoStatus').dataset.photo||''})});closeModal();load();toast('Family member added')}catch(x){toast(x.message)}}
async function delFamily(id){if(!confirm('Remove this family member?'))return;try{await api('/family/'+id,{method:'DELETE'});await load();toast('Member removed')}catch(x){toast(x.message)}}
async function takeDose(id){
  try{
    const shouldStop=activeReminder?.id==id;
    if(shouldStop) stopReminder(false);
    if(takenToday(id)){await load();toast('Dose already completed ✓');return;}
    await api('/history',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({medicineId:id})});
    await load();toast('Dose completed ✓');
  }catch(x){toast(x.message)}
}
function takenToday(id){
  let d=new Date();
  return history.some(x=>x.medicine_id==id&&new Date(x.taken_at).toDateString()===d.toDateString());
}
function customKey(id){return 'medimate-custom-ringtone-'+String(me?.id||'user')+'-'+id}
function saveCustomRingtone(id,data){ringtoneStore(id,data).catch(()=>{})}
async function getCustomRingtone(id){return ringtoneLoad(id)}
function removeCustomRingtone(id){ringtoneRemove(id)}
function openModal(){$('#modal').classList.remove('hidden')}
function closeModal(){$('#modal').classList.add('hidden')}
function fmt(t){let [h,m]=t.split(':');let d=new Date();d.setHours(+h,+m);return d.toLocaleTimeString([],{hour:'numeric',minute:'2-digit'})}
function esc(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function toast(s){let t=$('#toast');t.textContent=s;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),2800)}
async function photoChanged(e){let f=e.target.files[0];if(!f)return;if(f.size>2*1024*1024){toast('Photo must be under 2 MB');return}let reader=new FileReader();reader.onload=()=>{me.photo=reader.result;setPhoto($('#profilePhoto'),me);};reader.readAsDataURL(f)}
async function saveProfile(e){e.preventDefault();try{await api('/profile',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:$('#profileName').value,phone:$('#profilePhone').value,photo:me.photo||''})});me=await api('/auth/me');renderUser();toast('Profile updated ✓')}catch(x){toast(x.message)}}
function prepareAudio(){
  try{
    if(!audioCtx) audioCtx=new(window.AudioContext||window.webkitAudioContext)();
    if(audioCtx.state==='suspended') audioCtx.resume().catch(()=>{});
  }catch{}
}
function playTone(type){
  if(!audioCtx)return;
  let seq=type==='digital'?[660,880,660]:type==='gentle'?[523,659,784]:[784,988,784],n=audioCtx.currentTime;
  seq.forEach((f,i)=>{let o=audioCtx.createOscillator(),g=audioCtx.createGain();o.frequency.value=f;g.gain.setValueAtTime(.0001,n+i*.18);g.gain.exponentialRampToValueAtTime(.16,n+i*.18+.03);g.gain.exponentialRampToValueAtTime(.0001,n+i*.18+.16);o.connect(g).connect(audioCtx.destination);o.start(n+i*.18);o.stop(n+i*.18+.17)})
}
function startBuiltInLoop(type){
  stopAlarmAudio();
  if(!audioCtx)try{audioCtx=new(window.AudioContext||window.webkitAudioContext)()}catch{}
  if(!audioCtx)return;
  const seq=type==='digital'?[660,880,660]:type==='gentle'?[523,659,784]:[784,988,784];
  const step=type==='digital'?.18:.22;
  const duration=seq.length*step+.15;
  const loop=()=>{
    if(!activeReminder)return;
    playTone(type);
    alarmAudio={timer:setTimeout(loop,duration*1000)};
  };
  loop();
}
function startCustomLoop(data){
  stopAlarmAudio();
  alarmAudio=new Audio(data);
  alarmAudio.loop=true;
  alarmAudio.preload='auto';
  alarmAudio.volume=.95;
  const p=alarmAudio.play();
  if(p&&p.catch)p.catch(()=>toast('Click Turn Off after allowing sound in the browser.'));
}
function stopAlarmAudio(){
  if(!alarmAudio)return;
  try{
    if(alarmAudio instanceof HTMLAudioElement){alarmAudio.pause();alarmAudio.currentTime=0}
    else if(alarmAudio.timer)clearTimeout(alarmAudio.timer);
  }catch{}
  alarmAudio=null;
}
function showReminderOverlay(m,copy){
  $('#reminderTitle').textContent=copy.title;
  $('#reminderMedicine').textContent=copy.body;
  $('#reminderDose').textContent='';
  $('#reminderModal').classList.remove('hidden');
}
function hideReminderOverlay(){$('#reminderModal').classList.add('hidden')}
const notificationText={en:{title:'Medicine Reminder',body:n=>`It is time to take ${n}`,off:'Turn Off'},hi:{title:'दवा की याद दिलाने वाली सूचना',body:n=>`${n} लेने का समय हो गया है`,off:'बंद करें'},kn:{title:'ಔಷಧಿ ಜ್ಞಾಪನೆ',body:n=>`${n} ತೆಗೆದುಕೊಳ್ಳುವ ಸಮಯವಾಗಿದೆ`,off:'ಆಫ್ ಮಾಡಿ'}};
function setNotificationLanguage(v){notificationLanguage=v;localStorage.setItem('medimate-notification-language',v);}
function notificationCopy(m){const x=notificationText[notificationLanguage]||notificationText.en;const name=notificationLanguage==='hi'?(m.name_hi||m.name_en||m.name):notificationLanguage==='kn'?(m.name_kn||m.name_en||m.name):(m.name_en||m.name||'medicine');return {title:x.title,body:x.body(name),off:x.off}}
async function remind(m){
  activeReminder=m;
  const custom=m.ringtone==='custom'?await getCustomRingtone(m.id):'';
  if(custom)startCustomLoop(custom);else startBuiltInLoop(m.ringtone||'soft-chime');
  const copy=notificationCopy(m);
  $('#reminderStopText').textContent=copy.off;
  showReminderOverlay(m,copy);
  if(Notification.permission==='granted'){
    try{
      activeNativeNotification=new Notification(copy.title,{body:copy.body,tag:'medicine-'+m.id});
      activeNativeNotification.onclick=()=>window.focus();
    }catch{}
  }
}
async function stopReminder(markCompleted=true){
  const m=activeReminder;
  activeReminder=null;
  stopAlarmAudio();
  if(activeNativeNotification){try{activeNativeNotification.close()}catch{}activeNativeNotification=null}
  hideReminderOverlay();
  if(markCompleted&&m){
    try{
      if(!takenToday(m.id)){
        await api('/history',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({medicineId:m.id})});
        await load();
        toast('Dose completed ✓');
      }else{
        await load();
        toast('Dose already completed ✓');
      }
    }catch(x){toast(x.message)}
  }
}
function check(){
  let d=new Date(),hm=d.toTimeString().slice(0,5);
  medicines.forEach(m=>{
    let k='ring-'+d.toISOString().slice(0,10)+'-'+m.id;
    if(m.time===hm&&!sessionStorage.getItem(k)&&!takenToday(m.id)){
      sessionStorage.setItem(k,'1');remind(m)
    }
  })
}
setInterval(check,1000);
async function logout(show=true){stopReminder(false);try{await fetch('/api/auth/logout',{method:'POST'})}catch{}me=null;$('#app').classList.add('hidden');$('#authScreen').classList.remove('hidden');if(show)toast('Logged out')}
