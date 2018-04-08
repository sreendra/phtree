/*
 * Copyright 2011-2016 ETH Zurich. All Rights Reserved.
 *
 * This software is the proprietary information of ETH Zurich.
 * Use is subject to license terms.
 */
package ch.ethz.globis.phtree.v11hd2;

import java.util.Arrays;

import ch.ethz.globis.pht64kd.MaxKTreeHdI.NtEntry;
import ch.ethz.globis.phtree.PhEntry;
import ch.ethz.globis.phtree.PhFilter;
import ch.ethz.globis.phtree.PhTreeHelperHD;
import ch.ethz.globis.phtree.v11hd2.nt.NtIteratorMinMax;



/**
 * This NodeIterator reuses existing instances, which may be easier on the Java GC.
 * 
 * 
 * @author ztilmann
 *
 * @param <T> value type
 */
public class NodeIteratorFullNoGC<T> {
	
	private static final long[] FINISHED = new long[0]; 
	
	private final int dims;
	private int postLen;
	private long[] next;
	private Node node;
	private NtIteratorMinMax<Object> ntIterator;
	private final long[] valTemplate;
	private PhFilter checker;


	/**
	 * 
	 * @param dims dimensions
	 * @param valTemplate A null indicates that no values are to be extracted.
	 */
	public NodeIteratorFullNoGC(int dims, long[] valTemplate) {
		this.dims = dims;
		this.valTemplate = valTemplate;
	}
	
	/**
	 * 
	 * @param node
	 * @param rangeMin The minimum value that any found value should have. If the found value is
	 *  lower, the search continues.
	 * @param rangeMax
	 * @param lower The minimum HC-Pos that a value should have.
	 * @param upper
	 * @param checker result verifier, can be null.
	 */
	private void reinit(Node node, PhFilter checker) {
		next = null;
		this.checker = checker;
	
		this.node = node;
		this.postLen = node.getPostLen();
		
		
		//Position of the current entry
		if (ntIterator == null) {
			//TODO remove this..???
			long[] min = BitsHD.newArray(dims); //0
			long[] max = BitsHD.newArray(dims); //Long.MAX_VALUE
			Arrays.fill(max, -1L);
			ntIterator = new NtIteratorMinMax<>(dims, min, max);
		}
		ntIterator.reset(node.ind());
	}

	/**
	 * Advances the cursor. 
	 * @return TRUE iff a matching element was found.
	 */
	boolean increment(PhEntry<T> result) {
		getNext(result);
		return next != FINISHED;
	}

	/**
	 * 
	 * @return False if the value does not match the range, otherwise true.
	 */
	@SuppressWarnings("unchecked")
	private boolean readValue(long[] pos, long[] kdKey, Object value, PhEntry<T> result) {
		PhTreeHelperHD.applyHcPosHD(pos, postLen, valTemplate);
		if (value instanceof Node) {
			Node sub = (Node) value;
			node.getInfixOfSubNt(kdKey, valTemplate);
			if (checker != null && !checker.isValid(sub.getPostLen()+1, valTemplate)) {
				return false;
			}
			result.setNodeInternal(sub);
		} else {
			long[] resultKey = result.getKey();
			final long mask = (~0L)<<postLen;
			for (int i = 0; i < resultKey.length; i++) {
				resultKey[i] = (valTemplate[i] & mask) | kdKey[i];
			}

			if (checker != null && !checker.isValid(resultKey)) {
				return false;
			}

			//ensure that 'node' is set to null
			result.setValueInternal((T) value);
		}
		return true;
	}


	private void getNext(PhEntry<T> result) {
		niFindNext(result);
	}
	

	private void niFindNext(PhEntry<T> result) {
		while (ntIterator.hasNext()) {
			NtEntry<Object> e = ntIterator.nextEntryReuse();
			//TODO can we simplify this? e.key()/getKdKey() should should contain the
			//  complete value, or not? Why applyPos.. again (inside readValue)?
			if (readValue(e.key(), e.getKdKey(), e.value(), result)) {
				next = e.key();
				return;
			}
		}
		next = FINISHED;
	}

	void init(Node node, PhFilter checker) {
		reinit(node, checker);
	}

}
