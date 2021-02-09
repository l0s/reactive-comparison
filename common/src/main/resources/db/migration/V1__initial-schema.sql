--
-- Copyright © 2020 Carlos Macasaet
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     https://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

CREATE TABLE IF NOT EXISTS "User" (
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
    FOREIGN KEY( user_id ) REFERENCES "User"( id ) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS Message(
    ts TIMESTAMP NOT NULL,
    from_id UUID NOT NULL,
    body VARCHAR( 16384 ) NOT NULL,
    conversation_id UUID NOT NULL,
    id INTEGER NOT NULL,
    FOREIGN KEY( from_id ) REFERENCES "User"( id ) ON DELETE CASCADE,
    FOREIGN KEY( conversation_id ) REFERENCES Conversation( id ) ON DELETE CASCADE,
    PRIMARY KEY( conversation_id, id )
);