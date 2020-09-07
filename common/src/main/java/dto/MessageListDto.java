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
package dto;

import java.util.List;
import java.util.Objects;

import org.springframework.hateoas.RepresentationModel;

import domain.Message;

public class MessageListDto extends RepresentationModel<MessageListDto> {
    private List<Message> messages;

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(final List<Message> messages) {
        Objects.requireNonNull(messages);
        this.messages = messages;
    }
}