# PH-Tree

The PH-tree is a multi-dimensional indexing and storage structure.
By default it stores k-dimensional keys (points) consisting of k 64bit-integers. However, it can also be used to efficiently store floating point values and/or k-dimensional rectangles.
It supports kNN (k nearest neighbor) queries, range queries, window queries and fast update/moving of individual entries.

The PH-tree was developed at ETH Zurich and first published in:
"The PH-Tree: A Space-Efficient Storage Structure and Multi-Dimensional Index" ([PDF](http://globis.ethz.ch/?pubdownload=699)), 
Tilmann Zäschke, Christoph Zimmerli and Moira C. Norrie, 
Proceedings of Intl. Conf. on Management of Data (SIGMOD), 2014

The current version of the PH-tree is discussed in more detail here: ([PDF](https://github.com/tzaeschke/phtree/blob/master/PhTreeRevisited.pdf)) (2015).

Contact:
{zaeschke,zimmerli,norrie)@inf.ethz.ch


# Main Properties

### Advantages

- Memory efficient: Due to prefix sharing and other optimisations the tree may consume less memory than a flat array of integers/floats.
- Update efficiency: The performance of `insert()`, `update()` and `delete()` operations is almost independent of the size of the tree. For low dimensions performance may even improve with larger trees (> 1M entries).
- Scalability with size: The tree scales very with size especially with larger datasets with 1 million entries or more.
- Scalability with dimension: Updates and 'contains()' scale very well to the current maximum of 62 dimensions. Depending on the dataset, queries may scale up to 20 dimensions or more.
- Skewed data: The tree works very well with skewed datasets, it actually prefers skewed datasets over evenly distributed datasets. However, see below (Data Preprocessing) for an exception.
- Stability: The tree never performs rebalancing, but imbalance is inherently limited so it is not a concern (maximum depth is 64, see paper). The advantages are that any modification operation will never modify more than one node in the tree. This limits the possible CPU cost and IO cost of update operations. It also makes is suitable for concurrency.


### Disadvantages

- The current implementation will not work with more then 62 dimensions.
- Performance/size: the tree generally performs less well with smaller datasets, is is best used with 1 million entries or more.
- Performance/dimensionality: depending on the dataset, performance of queries may degrade when using data with more than 10-20 dimensions. 
- Data: The tree may degrade with extreme datasets, as described in the paper. However it will still perform better than traditional KD-trees. Furthermore, the degradation can be avoided by preprocessing the data, see below.
- Storage: The tree does not store references to the provided keys, instead it compresses the keys into in internal representation. As a result, when extracting keys (for example via queries), new objects (`long[]`) are created to carry the returned keys. This may cause load on the garbage collector if the keys are discarded afterwards. See the section about [iterators](#iterators) below on some strategies to avoid this problem. 



### Generally

- The tree performs best with large datasets with 1 million entries or more. Performance may actually increase with large datasets.
- The tree performs best on window queries or nearest neighbour queries that return few result (window queries: 1-1000) because of the comparatively high extraction cost of values. 


### Differences to original PH-Tree

- Support for rectangle data
- Support for k nearest neighbour queries
- Dedicated `update()` method that combines `put()` and `remove()`
- Automatic splitting of large nodes greatly improves update performance for data with more than 10 dimensions
- General performance improvements and reduced garbage collection load


# Interfaces / Abstract Classes

This archive contains four variants and multiple versions of the PH-tree.

The four variants are:

- `PhTree`          For point data with integer coordinates. This is the native storage format.
- `PhTreeF`         For point data with floating point coordinates.
- `PhTreeSolid`     For intervals/rectangles/boxes (solids) with integer coordinates.
- `PhTreeSolidF`    For intervals/rectangles/boxes (solids) with floating point coordinates.

They can be created with `PhTreeXYZ.create(dimensions)`. The default key-width is 64bit per dimension.
The old non-value API is still available in the `tst` folder.
All queries return specialised iterators that give direct access to key, value or entry.
The `queryAll()` methods return lists of entries and are especially useful for small result sets. 

The packages `ch.ethz.globis.pht.v*` contain different versions of the PH-tree. They are the actual implementations of the four interfaces mentioned above.
A higher version number usually (not always) indicates better performance in terms of base speed,
scalability (size and dimensionality) as well as storage requirements.


# Tuning Memory Usage

There is little point in using 32bit instead of 64bit integer values, because prefix sharing takes care of unused leading bits.
For floating point values, using a 32bit float instead of 64bit float should reduce memory usage
somewhat. However it is usually better to convert floating point values to integer values by multiplying them with a constant. For example multiply by 10E6 to preserve 6 digit floating point precision.
Also, chose the multiplier such that it is not higher than the precision requires.
For example, if you have a precision of 6 digits after the decimal point, then multiply all values
by 1,000,000 before casting the to (long) and adding them to the tree.

See also the section about [iterators](#iterators) on how to avoid GC from performing queries.


# Perfomance Optimisation

Suggestions for performance optimisation can also be found in the PDF "The PH-Tree revisited", which is available in this repository.

### Updates

For updating the keys of entries (AKA moving objects index), consider using `update()`. This function is about twice as fast for small displacements and at least as fast as a `put()`/`remove()` combination.

### Choose a Type of Query

- `queryExtent()`:      Fastest option when traversing (almost) all of the tree
- `query()`:            Fastest option for for average result size > 50 (depending on data)
- `queryAll()`:         Fastest option for for average result size < 50 (depending on data)
- `nearestNeighbour()`: Nearest neighbour query
- `rangeQuery()`:       Returns everything with a spherical range

### Iterators

All iterators return by default the value of a stored key/value pair. All iterators also provide
three specialised methods `nextKey()`, `nextValue()` and `nextEntry()` to return only the key, only the value (just as `next()`) or the combined entry object. Iterating over the entry object has the disadvantage that the entries need to be created and create load on the GC (garbage collector). However, the entries provide easy access to the key, especially for SOLID keys.

The `nextValue()` and `next()` methods do not cause any GC (garbage collector) load and simply return the value associated with the result key.
The `nextKey()` and `nextEntry()` always create new key objects or new key and additional `PhEntry` objects respectively. There are two ways to avoid this:
- During insert, one could store the key as part of the value, for example `insert(key, key)`. Then we can use the `next()` method to access the key without creating new objects. The disadvantage is that we are effectively storing the key twice, once as 'key' and once as 'value'. Since the PH-tree is quite memory efficient, this may still consume less memory than other trees. 
- During extraction, we can use the `PhQuery.nextEntryReuse()` method that is available in every iterator. It reuse `PhEntry` objects and key objects by resetting their content. Several calls to `nextEntryReuse()` may return the same object, but always with the appropriate content. The returned object is only valid until the next call to `nextEntryReuse()`.
The disadvantage is that the key and `PhEntry` objects need to be copied if they are needed locally beyond the next call to `nextEntryReuse()`.

Another way to reduce GC is to reuse the iterators when performing multiple queries. This can be done by calling `PhQuery.reset(..)`, which will abort the current query and reset the iterator to the first element that fits the min/max values provided in the `reset(..)` call. This can be useful because an iterator consists of more than hundred Java objects. In some scenarios this increased overall performance of about 20%.  


### Wrappers

Another optimization to avoid GC may be to avoid or reimplement the wrappers (`PhTreeF`, `PhTreeSolid` and `PhTreeSolidF`). With most calls they create internally temporary objects for coordinates that are passed on to the actual tree (for example it creates a `long[]` for every `put` or `contains`). A custom wrapper could reuse these temporary objects so that they cannot cause garbage collection.


### Data Preprocessing

__** Default **__

The default configuration of the PH-tree works quite well with most datasets. For floating point values it ensures that precision is fully maintained when points are converted to integer and back. The conversion is based on the IEEE bit representation, which is converted to an integer (see `BitTools.toSortableLong()`).   

__** Multiply **__

To optimise performance, it is usually worth trying to preprocess the data by multiplying it with a large integer and then cast it to `long`. The multiplier should be chosen such that the required precision is maintained. For example, if 6 fractional digits are required, the multiplier should be at least 10e6 or 10e7. There are some helper classes that provide predefined preprocessors that can be plugged into the trees. For example, a 3D rectangle-tree with an integer multiplier of 10e9 can be created with:  

`PhTreeSolidF.create(3, new PreProcessorRangeF.Multiply(3, 10e9));`

It is worth trying several multipliers, because performance may change considerably. For example,
for one of our tests we multiplied with 10e9, which performed 10-20% better than 10e8 or 10e10.
Typically, this oscillates with multiples of 1000, so 10e12 performs similar to 10e9. 

__** Shift / Add **__

If data should be stored as floats in IEEE representation (`BitTools.toSortableLong()`), consider adding a constant such that the whole value domain falls into a single exponent. I.e.
shift the values such that all values have the same exponent. It can also help to shift values
such that all values have a positive sign.

__** Heterogeneous **__

Heterogeneous data (different data types and value ranges in each dimension) can be problematic for the PH-Tree when performing queries (insert, update, delete, contains should not be affected).

For heterogeneous data (combination of floats, integers, boolean, ...) consider shifting the
values such that the min/max values in each dimension have a similar distance in the integer 
representation. For example a 3D tree: `[0...10][10..30][0..1000]` multiply the first dimension by
100 and the second by 50, so that all dimensions have a range of about 1000.

The above is true if all dimension are queried with similar selectivity. If range queries in the
above example would mainly constrain the 2nd and 3rd dimension, then the first dimension should
NOT be multiplied. In other words, the more selective queries are on a given dimension, the more
wide should the dimension spread over the tree, i.e. the dimension should be given a higher 
multiplier.

__** API Support **__

Data preprocessing can be automated using the `PreProcessor*` classes (partly known as `IntegerPP` or `ExponentPP` in the PDF documentation).

  
# License

The code is licensed under the Apache License 2.0.

The PH-tree (namespace `ch.ethz`) is copyright 2011-2016 by 
ETH Zurich,
Institute for Information Systems,
Universitätsstrasse 6,
8092 Zurich,
Switzerland.

The critbit tree (namespace `org.zoodb`) is copyright 2009-2016 by
Tilmann Zäschke,
zoodb@gmx.de.
The critbit tree is also separately available here: https://github.com/tzaeschke/critbit
