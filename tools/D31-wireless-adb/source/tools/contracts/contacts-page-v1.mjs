// 2026-09-12：冻结第十二批生产通讯录纯校验合同；运行时不依赖Web工作区。
// 来源：remote/docs/2026-09-12-D31第十二批通讯录分页合同.md；
// 校验实现摘自research/FreePBX_VPNnode_Web/contacts-pages.js的三个纯函数。
// 来源文件SHA-256：36D3CE0676395BBDC16729665BE259E144B19D527ED10274BAEA66A04891C815。
const record=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const integer=(v,min,max)=>Number.isSafeInteger(v)&&v>=min&&v<=max;
const uuid=v=>typeof v==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(v);
const need=(ok,code)=>{if(!ok)throw Object.assign(Error(code),{code});};
const clone=v=>JSON.parse(JSON.stringify(v));
const descriptorKeys=('owner_package snapshot_id status sampled_at_ms elapsed_ms record_count frames_received received_chars start_observed end_observed list_complete contact_values_emitted completion_scope all_sources_complete snapshot_consistency android_equivalence service_implementation max_records max_frames max_received_chars wait_budget_ms retention_ms max_page_records page_consistency storage cross_process_restart cross_boot').split(' ');
const commonKeys=['schema_version','action','ok','read_only','source','contact_type'];
const pageKeys=['items','offset','next_offset','has_more','page_complete','cursor_scope'];
export function normalizeContactsPageParams(params){
  need(record(params),'CONTACTS_PAGE_ARGUMENTS_INVALID');let keys;
  if(params.action==='open'){keys=['action','source'];need(params.source==='LOCAL','CONTACTS_PAGE_SOURCE_UNSUPPORTED');}
  else if(params.action==='page'){keys=['action','snapshot_id','offset','limit'];need(uuid(params.snapshot_id)&&integer(params.offset,0,4096)&&integer(params.limit,1,32),'CONTACTS_PAGE_ARGUMENTS_INVALID');}
  else if(params.action==='close'){keys=['action','snapshot_id'];need(uuid(params.snapshot_id),'CONTACTS_PAGE_ARGUMENTS_INVALID');}
  else need(false,'CONTACTS_PAGE_ARGUMENTS_INVALID');
  need(Object.keys(params).length===keys.length&&Object.keys(params).every(k=>keys.includes(k)),'CONTACTS_PAGE_ARGUMENTS_INVALID');return clone(params);
}
export function normalizeContactsPageResult(value,params){
  const p=normalizeContactsPageParams(params);need(record(value)&&value.schema_version===1&&value.action===p.action&&typeof value.ok==='boolean'&&value.read_only===true&&value.source==='nexui_messenger'&&value.contact_type==='LOCAL','CONTACTS_PAGE_RESULT_SCOPE');
  need(new TextEncoder().encode(JSON.stringify(value)).length<=65536,'CONTACTS_PAGE_RESULT_SIZE');
  if(p.action!=='open')need(value.snapshot_id===p.snapshot_id,'CONTACTS_SNAPSHOT_MISMATCH');
  if(!value.ok){need(typeof value.code==='string'&&/^CONTACTS_[A-Z0-9_]{1,96}$/.test(value.code),'CONTACTS_PAGE_ERROR_INVALID');need(Object.keys(value).every(k=>[...commonKeys,'code','snapshot_id'].includes(k)),'CONTACTS_PAGE_ERROR_INVALID');if(p.action==='open')need(value.snapshot_id===undefined,'CONTACTS_PAGE_ERROR_INVALID');return clone(value);}
  if(p.action==='close'){need(value.snapshot_closed===true&&Object.keys(value).every(k=>[...commonKeys,'snapshot_id','snapshot_closed'].includes(k)),'CONTACTS_PAGE_CLOSE_UNCONFIRMED');return clone(value);}
  need(uuid(value.snapshot_id)&&value.owner_package==='com.starnet.dial'&&value.status==='COMPLETED'&&integer(value.sampled_at_ms,1,8640000000000000)&&integer(value.record_count,0,4096)&&value.end_observed===true&&value.list_complete===true&&value.all_sources_complete===false&&value.snapshot_consistency==='NOT_PROVIDED_BY_VENDOR'&&value.completion_scope==='SELECTED_SOURCE_ALL_CONTACTS_REPLY'&&value.retention_ms===120000&&value.max_page_records===32&&value.page_consistency==='IMMUTABLE_RECEIVED_REPLY'&&value.storage==='APP_PROCESS_MEMORY'&&value.cross_process_restart===false&&value.cross_boot===false,'CONTACTS_PAGE_SNAPSHOT_SCOPE');
  for(const [key,max] of Object.entries({elapsed_ms:120000,frames_received:128,received_chars:1048576,max_records:4096,max_frames:128,max_received_chars:1048576,wait_budget_ms:120000}))if(value[key]!==undefined)need(integer(value[key],0,max),'CONTACTS_PAGE_SNAPSHOT_SCOPE');
  if(value.start_observed!==undefined)need(typeof value.start_observed==='boolean','CONTACTS_PAGE_SNAPSHOT_SCOPE');
  if(value.android_equivalence!==undefined)need(value.android_equivalence==='NOT_VERIFIED','CONTACTS_PAGE_SNAPSHOT_SCOPE');
  if(value.service_implementation!==undefined)need(value.service_implementation==='NOT_DECRYPTED','CONTACTS_PAGE_SNAPSHOT_SCOPE');
  const allowed=[...commonKeys,...descriptorKeys,...(p.action==='page'?pageKeys:[])];need(Object.keys(value).every(k=>allowed.includes(k)),'CONTACTS_PAGE_RESULT_FIELDS');
  if(p.action==='open'){need(value.contact_values_emitted===false,'CONTACTS_PAGE_OPEN_PERSONAL_FIELDS');return clone(value);}
  need(Array.isArray(value.items)&&value.items.every(record)&&value.items.length<=p.limit&&value.offset===p.offset&&value.next_offset===p.offset+value.items.length&&integer(value.next_offset,p.offset,value.record_count)&&value.has_more===(value.next_offset<value.record_count)&&value.page_complete===!value.has_more&&value.cursor_scope==='SAME_SNAPSHOT_ONLY'&&value.contact_values_emitted===(value.items.length>0)&&(!value.has_more||value.items.length>0),'CONTACTS_PAGE_CURSOR_INVALID');
  function depth(v,n=0){need(n<=16,'CONTACTS_PAGE_ITEM_INVALID');if(v&&typeof v==='object')for(const child of Object.values(v))depth(child,n+1);}
  for(const item of value.items){need(JSON.stringify(item).length<=8192,'CONTACTS_PAGE_ITEM_INVALID');depth(item);}
  return clone(value);
}
export function validateContactsPageSnapshot(open,page){need(record(open)&&record(page),'CONTACTS_SNAPSHOT_MISMATCH');for(const k of descriptorKeys)if(k!=='contact_values_emitted')need(JSON.stringify(open[k])===JSON.stringify(page[k]),'CONTACTS_SNAPSHOT_MISMATCH');return true;}
