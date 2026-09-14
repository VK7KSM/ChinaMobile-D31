// 工具本地的纯会话校验；导入时不读文件、不联网、不执行发布逻辑。
export function sessionCookie(session){
  const c=session.cookies?.find(c=>c.name==='elf_admin'&&['v.elfradio.net','.elfradio.net'].includes(c.domain));
  if(!c||!c.value||(c.expires>0&&c.expires*1000<=Date.now())||/[\r\n;]/.test(c.value))
    throw Error('管理员会话缺失或过期，请正常登录生产网页后更新会话文件');
  return 'elf_admin='+c.value;
}
