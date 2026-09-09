'use strict';
const { protectedPage } = require('../../utils/access');
const { categoryPage } = require('../../utils/budget-selection');
protectedPage(categoryPage('BODY'));
