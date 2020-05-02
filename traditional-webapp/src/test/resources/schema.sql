CREATE TABLE IF NOT EXISTS User(
    id UUID NOT NULL PRIMARY KEY,
    name VARCHAR( 256 ) NOT NULL
);

CREATE TABLE IF NOT EXISTS Conversation(
    id UUID NOT NULL PRIMARY KEY,
    next_message_id INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS Conversation_Participant(
    conversation_id UUID NOT NULL,
    user_id UUID NOT NULL,
    FOREIGN KEY( conversation_id ) REFERENCES Conversation( id ) ON DELETE CASCADE,
    FOREIGN KEY( user_id ) REFERENCES User( id ) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS Message(
    ts TIMESTAMP NOT NULL,
    from_id UUID NOT NULL,
    body VARCHAR( 8192 ) NOT NULL,
    conversation_id UUID NOT NULL,
    id INTEGER NOT NULL,
    FOREIGN KEY( from_id ) REFERENCES User( id ) ON DELETE CASCADE,
    FOREIGN KEY( conversation_id ) REFERENCES Conversation( id ) ON DELETE CASCADE,
    PRIMARY KEY( conversation_id, id )
);