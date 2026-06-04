/**
 * Connection types - ported from React typings/connection.ts
 */

export interface IConnectionExtendInfoItem {
	key: string;
	value: string;
}

export interface IConnectionEnv {
	id: number;
	name: string;
	shortName: string;
	color: string;
}

export interface IConnectionListItem {
	id: number;
	alias: string;
	environment: IConnectionEnv;
	type: string;
	supportDatabase: boolean;
	supportSchema: boolean;
	user: string;
}

export interface IConnectionDetails {
	id: number;
	alias: string;
	environment: IConnectionEnv;
	type: string;
	isAdmin: boolean;
	url: string;
	user: string;
	password: string;
	ConsoleOpenedStatus: 'y' | 'n';
	extendInfo: IConnectionExtendInfoItem[];
	environmentId: number;
	ssh: unknown;
	driverConfig: {
		jdbcDriver: string;
		jdbcDriverClass: string;
	};
	[key: string]: unknown;
}

export type ICreateConnectionDetails = Omit<IConnectionDetails, 'id'>;
