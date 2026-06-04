import common from './common';
import login from './login';
import connection from './connection';
import setting from './setting';
import team from './team';
import workspace from './workspace';
import chat from './chat';
import dashboard from './dashboard';
import catalog from './catalog';
import research from './research';

export default {
	lang: 'ko',
	...common,
	...login,
	...connection,
	...setting,
	...team,
	...workspace,
	...chat,
	...dashboard,
	...catalog,
	...research
};
