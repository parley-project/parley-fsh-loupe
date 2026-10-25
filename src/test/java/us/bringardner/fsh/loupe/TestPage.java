/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 */
package us.bringardner.fsh.loupe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.loupe.LoupeException;

/** Paging frames and variables: start and count from the client. */
public class TestPage {

	private static final List<Integer> ALL = List.of(0, 1, 2, 3, 4);

	@Test
	public void pages() {
		assertEquals(ALL, FshBackend.page(ALL, null, null));
		assertEquals(List.of(1, 2), FshBackend.page(ALL, 1, 2));
		assertEquals(List.of(3, 4), FshBackend.page(ALL, 3, 10));
		assertEquals(List.of(), FshBackend.page(ALL, 9, 2));
		assertEquals(List.of(), FshBackend.page(ALL, 2, 0));
	}

	@Test
	public void aHugeCountIsTheRest() {
		assertEquals(List.of(2, 3, 4), FshBackend.page(ALL, 2, Integer.MAX_VALUE));
	}

	@Test
	public void negativesAreRefused() {
		assertEquals(LoupeException.INVALID_PARAMS,
				assertThrows(LoupeException.class, ()->FshBackend.page(ALL, -1, 2)).getCode());
		assertEquals(LoupeException.INVALID_PARAMS,
				assertThrows(LoupeException.class, ()->FshBackend.page(ALL, 0, -2)).getCode());
	}
}
