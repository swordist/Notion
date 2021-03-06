

CREATE TABLE LOOKUP (
	LookupKey INT NOT NULL PRIMARY KEY GENERATED ALWAYS AS IDENTITY ,
	PoolKey INT NOT NULL,
	Type VARCHAR(32),
	Name VARCHAR(250),
	Value VARCHAR(250),
	Visible BOOLEAN NOT NULL DEFAULT true,
	CONSTRAINT LOOKUP_fk1
      foreign key ( PoolKey ) references POOL(PoolKey) on delete cascade
	);

CREATE INDEX Lookup_idx1 on LOOKUP ( PoolKey, Type );
CREATE INDEX Lookup_idx3 on LOOKUP ( PoolKey, Visible );
CREATE INDEX Lookup_idx2 on LOOKUP ( PoolKey, Type, Name );


CREATE TABLE SCRIPT (
	ScriptKey INT NOT NULL PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
	PoolKey INT NOT NULL,
	Tag VARCHAR(64),
	Script VARCHAR(32000),
	CONSTRAINT SCRIPT_fk1
    	foreign key ( PoolKey ) references POOL(PoolKey) on delete cascade
);

CREATE INDEX Script_idx1 on SCRIPT ( PoolKey, Tag );
CREATE INDEX Script_idx2 on SCRIPT ( PoolKey );


