/**
 * Copyright © 2020 Carlos Macasaet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package blocking.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import blocking.repository.ConversationRepository;
import blocking.repository.UserRepository;
import domain.Conversation;
import domain.Message;

@ExtendWith(MockitoExtension.class)
class ConversationsControllerTest {

    private Clock clock = Clock.systemUTC();
    @Mock
    private UserRepository userRepository;
    @Mock
    private ConversationRepository repository;

    private ConversationsController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ConversationsController(clock, repository, userRepository);
    }

    @AfterEach
    void tearDown() throws Exception {
    }

    @Test
    public final void verifyGetMessagesReturnsNotFound() {
        // given
        final var invalidConversationId = UUID.randomUUID();
        given(repository.findConversation(invalidConversationId)).willReturn(null);

        // when
        final var result = controller.getMessages(invalidConversationId.toString(), 8, null);

        // then
        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
    }

    @Test
    public final void verifyGetMessagesReturnsMessage() {
        // given
        final var conversationId = UUID.randomUUID();
        final var conversation = mock(Conversation.class);
        final var message = new Message(conversationId, UUID.randomUUID(), clock.instant().atOffset(ZoneOffset.UTC),
                "hello", 3);
        given(repository.findConversation(conversationId)).willReturn(conversation);
        given(repository.findMessages(eq(conversation), anyInt(), anyInt()))
                .willReturn(Collections.singletonList(message));

        // when
        final var result = controller.getMessages(conversationId.toString(), 8, null);

        // then
        assertEquals(HttpStatus.OK, result.getStatusCode());
        final var listDto = result.getBody();
        assertEquals(1, listDto.getMessages().size());
        assertEquals("hello", listDto.getMessages().get(0).getBody());
    }

}
