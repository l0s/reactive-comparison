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
package integrationtest;

enum TimingMetric {
    CREATE_ALL_USERS,
    CREATE_SINGLE_USER,
    PAGE_THROUGH_ALL_USERS,
    GET_PAGE_OF_USERS,
    SEND_RECEIVE_ALL_MESSAGES,
    SEND_SINGLE_MESSAGE,
    GET_MESSAGES_FOR_USER,
}